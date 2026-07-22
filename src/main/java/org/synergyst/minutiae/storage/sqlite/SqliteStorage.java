package org.synergyst.minutiae.storage.sqlite;

import org.synergyst.minutiae.async.AsyncScheduler;
import org.synergyst.minutiae.config.StorageConfig;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.storage.*;
import org.synergyst.minutiae.storage.migration.MigrationRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * SQLite-backed {@link Storage} implementation.
 *
 * <p>The database file is resolved relative to the plugin data directory unless
 * the configured path is absolute. The JDBC driver is loaded explicitly to
 * guarantee registration; service-loader auto-registration is not relied upon.
 *
 * <p>On boot, the connection pool is opened, schema migrations are applied
 * against a single borrowed connection, and the resulting schema version is
 * cached for synchronous retrieval. All post-boot data access is dispatched
 * through the asynchronous scheduler; no method on this class blocks the calling
 * thread on I/O. Read operations borrow, query, and release a pooled connection;
 * multi-statement write operations run within an explicit transaction and roll
 * back on failure.
 */
public final class SqliteStorage implements Storage {

    private final KernelLogger log;
    private final StorageConfig config;
    private final AsyncScheduler scheduler;
    private final Path dataFolder;

    private ConnectionPool pool;
    private volatile int schemaVersion;

    public SqliteStorage(final KernelLogger log,
                         final StorageConfig config,
                         final AsyncScheduler scheduler,
                         final Path dataFolder) {
        this.log = log;
        this.config = config;
        this.scheduler = scheduler;
        this.dataFolder = dataFolder;
    }

    @Override
    public String tag() {
        return "storage";
    }

    @Override
    public void boot() {
        final Path dbPath = resolveDatabasePath();
        log.info("storage", "backend=SQLITE file=%s", dbPath);

        loadDriver();

        final String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        this.pool = new ConnectionPool(log, config, jdbcUrl);

        final Connection connection = pool.borrow();
        try {
            final MigrationRunner runner = new MigrationRunner(log);
            this.schemaVersion = runner.migrate(connection);
        } finally {
            pool.release(connection);
        }

        log.info("storage", "online; schema version=%d", schemaVersion);
    }

    @Override
    public void shutdown() {
        if (pool != null) {
            pool.close();
        }
    }

    @Override
    public int schemaVersion() {
        return schemaVersion;
    }

    // ----------------------------------------------------------------------
    // Liveness
    // ----------------------------------------------------------------------

    @Override
    public CompletableFuture<StoragePing> ping() {
        return scheduler.supply(() -> {
            final long start = System.nanoTime();
            final Connection connection = pool.borrow();
            try (final Statement st = connection.createStatement();
                 final ResultSet rs = st.executeQuery("SELECT 1")) {
                final boolean ok = rs.next() && rs.getInt(1) == 1;
                final long elapsed = System.nanoTime() - start;
                return new StoragePing(ok, elapsed, schemaVersion);
            } catch (final SQLException e) {
                log.warn("storage", "ping failed: %s", e.getMessage());
                return new StoragePing(false, System.nanoTime() - start, -1);
            } finally {
                pool.release(connection);
            }
        });
    }

    // ----------------------------------------------------------------------
    // Rule cache
    // ----------------------------------------------------------------------

    @Override
    public CompletableFuture<RuleSyncResult> syncRuleCache(final String[] ids,
                                                           final String[] descriptions,
                                                           final int[] hashes) {
        return scheduler.supply(() -> {
            final Connection connection = pool.borrow();
            try {
                connection.setAutoCommit(false);

                final Map<String, Integer> existing = new HashMap<>();
                try (final Statement st = connection.createStatement();
                     final ResultSet rs = st.executeQuery(
                             "SELECT rule, content_hash FROM rules_cache")) {
                    while (rs.next()) {
                        existing.put(rs.getString(1), rs.getInt(2));
                    }
                }

                int added = 0;
                int changed = 0;
                int unchanged = 0;
                final Set<String> incoming = new HashSet<>(ids.length * 2);

                try (final PreparedStatement upsert = connection.prepareStatement(
                        "INSERT INTO rules_cache (rule, description, content_hash) "
                                + "VALUES (?, ?, ?) "
                                + "ON CONFLICT(rule) DO UPDATE SET "
                                + "description = excluded.description, "
                                + "content_hash = excluded.content_hash")) {
                    for (int i = 0; i < ids.length; i++) {
                        incoming.add(ids[i]);
                        final Integer prior = existing.get(ids[i]);
                        if (prior == null) {
                            added++;
                        } else if (prior != hashes[i]) {
                            changed++;
                        } else {
                            unchanged++;
                            continue;
                        }
                        upsert.setString(1, ids[i]);
                        upsert.setString(2, descriptions[i]);
                        upsert.setInt(3, hashes[i]);
                        upsert.addBatch();
                    }
                    upsert.executeBatch();
                }

                int removed = 0;
                try (final PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM rules_cache WHERE rule = ?")) {
                    for (final String cached : existing.keySet()) {
                        if (!incoming.contains(cached)) {
                            delete.setString(1, cached);
                            delete.addBatch();
                            removed++;
                        }
                    }
                    delete.executeBatch();
                }

                connection.commit();
                return new RuleSyncResult(added, changed, removed, unchanged);
            } catch (final SQLException e) {
                safeRollback(connection);
                throw new StorageException("rule cache synchronisation failed", e);
            } finally {
                restoreAutoCommit(connection);
                pool.release(connection);
            }
        });
    }

    // ----------------------------------------------------------------------
    // Sanctions
    // ----------------------------------------------------------------------

    @Override
    public CompletableFuture<Integer> activateStays(final String uuid, final long now) {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "UPDATE bans SET active = 1, stayed = 0 "
                            + "WHERE uuid = ? AND stayed = 1 AND stay_until > ?")) {
                ps.setString(1, uuid);
                ps.setLong(2, now);
                return ps.executeUpdate();
            } catch (final SQLException e) {
                throw new StorageException("stay activation failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<ActiveBan> activeConnectionBan(final String uuid, final long now) {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT measure, reason, expires_at, behaviour_mask FROM bans "
                            + "WHERE uuid = ? AND active = 1 AND stayed = 0 "
                            + "AND measure IN ('SUSPENSION','CUSTODY') "
                            + "AND (expires_at = 0 OR expires_at > ?) "
                            + "ORDER BY (measure = 'CUSTODY') DESC, expires_at DESC LIMIT 1")) {
                ps.setString(1, uuid);
                ps.setLong(2, now);
                try (final ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return new ActiveBan(rs.getString(1), rs.getString(2),
                            rs.getLong(3), rs.getLong(4));
                }
            } catch (final SQLException e) {
                throw new StorageException("active ban query failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<BehaviourRow>> activeBehaviours(final String uuid, final long now) {
        return scheduler.supply(() -> {
            final List<BehaviourRow> rows = new ArrayList<>(4);
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT behaviour_mask, expires_at, reason FROM bans "
                            + "WHERE uuid = ? AND active = 1 AND stayed = 0 "
                            + "AND behaviour_mask != 0 "
                            + "AND (expires_at = 0 OR expires_at > ?)")) {
                ps.setString(1, uuid);
                ps.setLong(2, now);
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new BehaviourRow(rs.getLong(1), rs.getLong(2), rs.getString(3)));
                    }
                }
                return rows;
            } catch (final SQLException e) {
                throw new StorageException("active behaviours query failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    // ----------------------------------------------------------------------
    // Fingerprint signals
    // ----------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> persistSignals(final long banId,
                                                  final int[] types,
                                                  final String[] values,
                                                  final double[] weights) {
        if (types.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        return scheduler.run(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO signals (ban_id, type, value, weight) VALUES (?, ?, ?, ?)")) {
                for (int i = 0; i < types.length; i++) {
                    ps.setLong(1, banId);
                    ps.setInt(2, types[i]);
                    ps.setString(3, values[i]);
                    ps.setDouble(4, weights[i]);
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (final SQLException e) {
                throw new StorageException("failed to persist signals", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<EvasionMatch> scoreEvasion(final long now,
                                                        final int[] types,
                                                        final String[] values) {
        if (types.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        return scheduler.supply(() -> {
            final StringBuilder sql = new StringBuilder(320);
            sql.append("SELECT b.uuid, SUM(s.weight) AS score, COUNT(*) AS hits ")
                    .append("FROM signals s ")
                    .append("JOIN bans b ON b.id = s.ban_id ")
                    .append("WHERE b.active = 1 AND b.stayed = 0 ")
                    .append("AND b.measure IN ('SUSPENSION','CUSTODY') ")
                    .append("AND (b.expires_at = 0 OR b.expires_at > ?) ")
                    .append("AND (");
            for (int i = 0; i < types.length; i++) {
                if (i > 0) {
                    sql.append(" OR ");
                }
                sql.append("(s.type = ? AND s.value = ?)");
            }
            sql.append(") GROUP BY s.ban_id ORDER BY score DESC LIMIT 1");

            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(sql.toString())) {
                int idx = 1;
                ps.setLong(idx++, now);
                for (int i = 0; i < types.length; i++) {
                    ps.setInt(idx++, types[i]);
                    ps.setString(idx++, values[i]);
                }
                try (final ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return new EvasionMatch(rs.getString(1), rs.getDouble(2), rs.getInt(3));
                }
            } catch (final SQLException e) {
                throw new StorageException("evasion scoring query failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> getVerbose(final String uuid) {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT verbose FROM preferences WHERE uuid = ?")) {
                ps.setString(1, uuid);
                try (final ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) != 0 : Boolean.TRUE;
                }
            } catch (final SQLException e) {
                throw new StorageException("verbose preference query failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Void> setVerbose(final String uuid, final boolean verbose) {
        return scheduler.run(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO preferences (uuid, verbose) VALUES (?, ?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET verbose = excluded.verbose")) {
                ps.setString(1, uuid);
                ps.setInt(2, verbose ? 1 : 0);
                ps.executeUpdate();
            } catch (final SQLException e) {
                throw new StorageException("verbose preference write failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<SanctionView> findSanction(final long id) {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT " + SANCTION_COLUMNS + " FROM bans WHERE id = ?")) {
                ps.setLong(1, id);
                try (final ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return readSanctionView(rs);
                }
            } catch (final SQLException e) {
                throw new StorageException("sanction lookup failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> liftSanction(final long id, final String actor,
                                                   final long at, final String reason,
                                                   final int liftKind, final long probationUntil) {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try {
                c.setAutoCommit(false);
                final int rows;
                try (final PreparedStatement ps = c.prepareStatement(
                        "UPDATE bans SET active = 0, stayed = 0, "
                                + "lifted_at = ?, lifted_by = ?, lift_reason = ?, lift_kind = ?, "
                                + "probation_until = CASE WHEN ? > 0 THEN ? ELSE probation_until END "
                                + "WHERE id = ? AND lifted_at = 0")) {
                    ps.setLong(1, at);
                    ps.setString(2, actor);
                    ps.setString(3, reason);
                    ps.setInt(4, liftKind);
                    ps.setLong(5, probationUntil);
                    ps.setLong(6, probationUntil);
                    ps.setLong(7, id);
                    rows = ps.executeUpdate();
                }
                if (rows > 0) {
                    try (final PreparedStatement audit = c.prepareStatement(
                            "INSERT INTO audit (ban_id, action, actor, ts, detail) "
                                    + "VALUES (?, 'LIFT', ?, ?, ?)")) {
                        audit.setLong(1, id);
                        audit.setString(2, actor);
                        audit.setLong(3, at);
                        audit.setString(4, reason);
                        audit.executeUpdate();
                    }
                }
                c.commit();
                return rows;
            } catch (final SQLException e) {
                safeRollback(c);
                throw new StorageException("sanction lift failed", e);
            } finally {
                restoreAutoCommit(c);
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> countLiftable(final BulkCriterion criterion,
                                                    final String value) {
        final String column = switch (criterion) {
            case RULE -> "rule";
            case STAFF -> "staff";
        };
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM bans WHERE " + column + " = ? "
                            + "AND lifted_at = 0 AND (active = 1 OR stayed = 1 OR suspended = 1)")) {
                ps.setString(1, value);
                try (final ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } catch (final SQLException e) {
                throw new StorageException("bulk lift count failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> bulkLift(final BulkCriterion criterion, final String value,
                                               final String actor, final long at,
                                               final String reason) {
        final String column = switch (criterion) {
            case RULE -> "rule";
            case STAFF -> "staff";
        };
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try {
                c.setAutoCommit(false);
                final int rows;
                try (final PreparedStatement ps = c.prepareStatement(
                        "UPDATE bans SET active = 0, stayed = 0, suspended = 0, "
                                + "lifted_at = ?, lifted_by = ?, lift_reason = ?, lift_kind = 0 "
                                + "WHERE " + column + " = ? AND lifted_at = 0 "
                                + "AND (active = 1 OR stayed = 1 OR suspended = 1)")) {
                    ps.setLong(1, at);
                    ps.setString(2, actor);
                    ps.setString(3, reason);
                    ps.setString(4, value);
                    rows = ps.executeUpdate();
                }
                if (rows > 0) {
                    try (final PreparedStatement audit = c.prepareStatement(
                            "INSERT INTO audit (ban_id, action, actor, ts, detail) "
                                    + "VALUES (NULL, 'BULK_LIFT', ?, ?, ?)")) {
                        audit.setString(1, actor);
                        audit.setLong(2, at);
                        audit.setString(3, criterion.name().toLowerCase(java.util.Locale.ROOT)
                                + "=" + value + " count=" + rows
                                + (reason != null ? " reason=" + reason : ""));
                        audit.executeUpdate();
                    }
                }
                c.commit();
                return rows;
            } catch (final SQLException e) {
                safeRollback(c);
                throw new StorageException("bulk lift failed", e);
            } finally {
                restoreAutoCommit(c);
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> unliftSanction(final long id, final String actor,
                                                     final long at, final String reason) {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try {
                c.setAutoCommit(false);
                final int rows;
                try (final PreparedStatement ps = c.prepareStatement(
                        "UPDATE bans SET lifted_at = 0, lifted_by = NULL, lift_reason = NULL, "
                                + "lift_kind = 0, "
                                + "active = CASE "
                                + "WHEN measure IN ('WARN','CENSURE','KICK') THEN 0 "
                                + "WHEN expires_at <> 0 AND expires_at <= ? THEN 0 "
                                + "ELSE 1 END "
                                + "WHERE id = ? AND lifted_at <> 0")) {
                    ps.setLong(1, at);
                    ps.setLong(2, id);
                    rows = ps.executeUpdate();
                }
                if (rows > 0) {
                    try (final PreparedStatement audit = c.prepareStatement(
                            "INSERT INTO audit (ban_id, action, actor, ts, detail) "
                                    + "VALUES (?, 'UNLIFT', ?, ?, ?)")) {
                        audit.setLong(1, id);
                        audit.setString(2, actor);
                        audit.setLong(3, at);
                        audit.setString(4, reason);
                        audit.executeUpdate();
                    }
                }
                c.commit();
                return rows;
            } catch (final SQLException e) {
                safeRollback(c);
                throw new StorageException("sanction unlift failed", e);
            } finally {
                restoreAutoCommit(c);
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<SanctionView>> listSanctions(final String uuid,
                                                               final int limit, final int offset) {
        return scheduler.supply(() -> {
            final List<SanctionView> out = new ArrayList<>(limit);
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT " + SANCTION_COLUMNS + " FROM bans "
                            + "WHERE uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, uuid);
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(readSanctionView(rs));
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("sanction listing failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> countSanctions(final String uuid) {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM bans WHERE uuid = ?")) {
                ps.setString(1, uuid);
                try (final ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } catch (final SQLException e) {
                throw new StorageException("sanction count failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Long> persistSanction(final SanctionRow row, final String[] extraProvisions) {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try {
                c.setAutoCommit(false);
                final long id;
                try (final PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO bans (uuid, layout, rule, reason, issued_at, expires_at, "
                                + "staff, annotations, active, measure, stayed, stay_until, link, "
                                + "behaviour_mask, provisional, decay_at, appealable, parent_id, "
                                + "probation_until, subject_name, server_id, suspended, "
                                + "suspend_until, expunge_at, review_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, row.uuid());
                    ps.setString(2, row.layout());
                    ps.setString(3, row.rule());
                    ps.setString(4, row.reason());
                    ps.setLong(5, row.issuedAt());
                    ps.setLong(6, row.expiresAt());
                    ps.setString(7, row.staff());
                    ps.setString(8, row.annotations());
                    ps.setInt(9, row.active());
                    ps.setString(10, row.measure());
                    ps.setInt(11, row.stayed());
                    ps.setLong(12, row.stayUntil());
                    ps.setString(13, row.link());
                    ps.setLong(14, row.behaviourMask());
                    ps.setInt(15, row.provisional());
                    ps.setLong(16, row.decayAt());
                    ps.setInt(17, row.appealable());
                    ps.setLong(18, row.parentId());
                    ps.setLong(19, row.probationUntil());
                    ps.setString(20, row.subjectName() == null ? "" : row.subjectName());
                    ps.setString(21, row.serverId() == null ? "" : row.serverId());
                    ps.setInt(22, row.suspended());
                    ps.setLong(23, row.suspendUntil());
                    ps.setLong(24, row.expungeAt());
                    ps.setLong(25, row.reviewAt());
                    ps.executeUpdate();
                    try (final ResultSet keys = ps.getGeneratedKeys()) {
                        id = keys.next() ? keys.getLong(1) : -1L;
                    }
                }
                if (extraProvisions.length > 0 && id > 0) {
                    try (final PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO provisions (ban_id, rule) VALUES (?, ?)")) {
                        for (final String rule : extraProvisions) {
                            ps.setLong(1, id);
                            ps.setString(2, rule);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                c.commit();
                return id;
            } catch (final SQLException e) {
                safeRollback(c);
                throw new StorageException("failed to persist sanction", e);
            } finally {
                restoreAutoCommit(c);
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Precedent> precedent(final String uuid, final String rule, final long now) {
        if (rule == null) {
            return CompletableFuture.completedFuture(Precedent.NONE);
        }
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT "
                            + "SUM(CASE WHEN measure = 'WARN' THEN 0 ELSE 1 END) AS reals, "
                            + "SUM(CASE WHEN measure = 'WARN' THEN 1 ELSE 0 END) AS warns, "
                            + "MAX(CASE WHEN probation_until > ? THEN 1 ELSE 0 END) AS probation "
                            + "FROM bans "
                            + "WHERE uuid = ? AND rule = ? "
                            + "AND (lifted_at = 0 OR lift_kind <> 0) "
                            + "AND (decay_at = 0 OR decay_at > ?)")) {
                ps.setLong(1, now);
                ps.setString(2, uuid);
                ps.setString(3, rule);
                ps.setLong(4, now);
                try (final ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Precedent.NONE;
                    }
                    return new Precedent(rs.getInt(1), rs.getInt(2), rs.getInt(3) != 0);
                }
            } catch (final SQLException e) {
                throw new StorageException("precedent query failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Precedent> chainPrecedent(final long nodeId, final long now) {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try {
                long root = nodeId;
                try (final PreparedStatement up = c.prepareStatement(
                        "SELECT parent_id FROM bans WHERE id = ?")) {
                    while (true) {
                        up.setLong(1, root);
                        final long parent;
                        try (final ResultSet rs = up.executeQuery()) {
                            if (!rs.next()) {
                                break;
                            }
                            parent = rs.getLong(1);
                        }
                        if (parent == 0L) {
                            break;
                        }
                        root = parent;
                    }
                }
                try (final PreparedStatement ps = c.prepareStatement(
                        "WITH RECURSIVE chain(id) AS ("
                                + "SELECT id FROM bans WHERE id = ? "
                                + "UNION ALL "
                                + "SELECT b.id FROM bans b JOIN chain ON b.parent_id = chain.id) "
                                + "SELECT "
                                + "SUM(CASE WHEN measure = 'WARN' THEN 0 ELSE 1 END), "
                                + "SUM(CASE WHEN measure = 'WARN' THEN 1 ELSE 0 END), "
                                + "MAX(CASE WHEN probation_until > ? THEN 1 ELSE 0 END) "
                                + "FROM bans WHERE id IN (SELECT id FROM chain) "
                                + "AND (lifted_at = 0 OR lift_kind <> 0) "
                                + "AND (decay_at = 0 OR decay_at > ?)")) {
                    ps.setLong(1, root);
                    ps.setLong(2, now);
                    ps.setLong(3, now);
                    try (final ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return Precedent.NONE;
                        }
                        return new Precedent(rs.getInt(1), rs.getInt(2), rs.getInt(3) != 0);
                    }
                }
            } catch (final SQLException e) {
                throw new StorageException("chain precedent query failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<AmendResult> amendSanction(final long id, final String rule,
                                                        final String reason, final Long expiresAt,
                                                        final String[] counts, final String actor,
                                                        final long now) {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try {
                c.setAutoCommit(false);
                final String uuid, oldRule, oldReason, measure;
                final long oldExpires, mask;
                try (final PreparedStatement ps = c.prepareStatement(
                        "SELECT uuid, rule, reason, expires_at, measure, behaviour_mask "
                                + "FROM bans WHERE id = ?")) {
                    ps.setLong(1, id);
                    try (final ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            c.rollback();
                            return new AmendResult(false, "", null, null, 0L, 0L);
                        }
                        uuid = rs.getString(1);
                        oldRule = rs.getString(2);
                        oldReason = rs.getString(3);
                        oldExpires = rs.getLong(4);
                        measure = rs.getString(5);
                        mask = rs.getLong(6);
                    }
                }

                final StringBuilder diff = new StringBuilder(48);
                final List<String> sets = new ArrayList<>(3);
                final List<Object> params = new ArrayList<>(3);

                if (rule != null && !rule.equals(oldRule)) {
                    sets.add("rule = ?");
                    params.add(rule);
                    diff.append("rule '").append(oldRule).append("'->'").append(rule).append("' ");
                }
                if (reason != null && !reason.equals(oldReason)) {
                    sets.add("reason = ?");
                    params.add(reason);
                    diff.append("reason changed ");
                }
                long newExpires = oldExpires;
                if (expiresAt != null && expiresAt != oldExpires) {
                    sets.add("expires_at = ?");
                    params.add(expiresAt);
                    newExpires = expiresAt;
                    diff.append("expiry ").append(oldExpires).append("->").append(expiresAt).append(' ');
                }

                if (!sets.isEmpty()) {
                    final String sql = "UPDATE bans SET " + String.join(", ", sets) + " WHERE id = ?";
                    try (final PreparedStatement ps = c.prepareStatement(sql)) {
                        int i = 1;
                        for (final Object p : params) {
                            if (p instanceof Long l) {
                                ps.setLong(i++, l);
                            } else {
                                ps.setString(i++, (String) p);
                            }
                        }
                        ps.setLong(i, id);
                        ps.executeUpdate();
                    }
                }

                if (counts.length > 0) {
                    try (final PreparedStatement del = c.prepareStatement(
                            "DELETE FROM provisions WHERE ban_id = ?")) {
                        del.setLong(1, id);
                        del.executeUpdate();
                    }
                    try (final PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO provisions (ban_id, rule) VALUES (?, ?)")) {
                        for (final String rr : counts) {
                            ins.setLong(1, id);
                            ins.setString(2, rr);
                            ins.addBatch();
                        }
                        ins.executeBatch();
                    }
                    diff.append("counts=").append(counts.length).append(' ');
                }

                final String detail = diff.length() == 0 ? "no changes" : diff.toString().trim();
                try (final PreparedStatement audit = c.prepareStatement(
                        "INSERT INTO audit (ban_id, action, actor, ts, detail) "
                                + "VALUES (?, 'AMEND', ?, ?, ?)")) {
                    audit.setLong(1, id);
                    audit.setString(2, actor);
                    audit.setLong(3, now);
                    audit.setString(4, detail);
                    audit.executeUpdate();
                }

                c.commit();
                return new AmendResult(true, detail, uuid, measure, newExpires, mask);
            } catch (final SQLException e) {
                safeRollback(c);
                throw new StorageException("amend failed", e);
            } finally {
                restoreAutoCommit(c);
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Long> submitAppeal(final long banId, final String appellant,
                                                final String text, final long now) {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try {
                c.setAutoCommit(false);
                try (final PreparedStatement chk = c.prepareStatement(
                        "SELECT COUNT(*) FROM appeals WHERE ban_id = ? AND status = 'PENDING'")) {
                    chk.setLong(1, banId);
                    try (final ResultSet rs = chk.executeQuery()) {
                        if (rs.next() && rs.getInt(1) > 0) {
                            c.rollback();
                            return -1L;
                        }
                    }
                }
                final long id;
                try (final PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO appeals (ban_id, appellant, text, status, created_at) "
                                + "VALUES (?, ?, ?, 'PENDING', ?)", Statement.RETURN_GENERATED_KEYS)) {
                    ins.setLong(1, banId);
                    ins.setString(2, appellant);
                    ins.setString(3, text);
                    ins.setLong(4, now);
                    ins.executeUpdate();
                    try (final ResultSet keys = ins.getGeneratedKeys()) {
                        id = keys.next() ? keys.getLong(1) : -1L;
                    }
                }
                c.commit();
                return id;
            } catch (final SQLException e) {
                safeRollback(c);
                throw new StorageException("appeal submission failed", e);
            } finally {
                restoreAutoCommit(c);
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> countPendingAppeals() {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM appeals WHERE status = 'PENDING'");
                 final ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            } catch (final SQLException e) {
                throw new StorageException("pending appeal count failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<AppealView>> listPendingAppeals(final int limit, final int offset) {
        return scheduler.supply(() -> {
            final List<AppealView> out = new ArrayList<>(limit);
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT id, ban_id, appellant, text, status, verdict, reviewer, created_at, decided_at "
                            + "FROM appeals WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT ? OFFSET ?")) {
                ps.setInt(1, limit);
                ps.setInt(2, offset);
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(readAppeal(rs));
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("pending appeal listing failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<AppealView> findAppeal(final long id) {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT id, ban_id, appellant, text, status, verdict, reviewer, created_at, decided_at "
                            + "FROM appeals WHERE id = ?")) {
                ps.setLong(1, id);
                try (final ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? readAppeal(rs) : null;
                }
            } catch (final SQLException e) {
                throw new StorageException("appeal lookup failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> decideAppeal(final long id, final String status,
                                                   final String verdict, final String reviewer,
                                                   final long now) {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "UPDATE appeals SET status = ?, verdict = ?, reviewer = ?, decided_at = ? "
                            + "WHERE id = ? AND status = 'PENDING'")) {
                ps.setString(1, status);
                ps.setString(2, verdict);
                ps.setString(3, reviewer);
                ps.setLong(4, now);
                ps.setLong(5, id);
                return ps.executeUpdate();
            } catch (final SQLException e) {
                throw new StorageException("appeal decision failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Void> recordAudit(final Long banId, final String action,
                                               final String actor, final long ts, final String detail) {
        return scheduler.run(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO audit (ban_id, action, actor, ts, detail) VALUES (?, ?, ?, ?, ?)")) {
                if (banId == null) {
                    ps.setNull(1, java.sql.Types.INTEGER);
                } else {
                    ps.setLong(1, banId);
                }
                ps.setString(2, action);
                ps.setString(3, actor);
                ps.setLong(4, ts);
                ps.setString(5, detail);
                ps.executeUpdate();
            } catch (final SQLException e) {
                throw new StorageException("audit insert failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    private static AppealView readAppeal(final ResultSet rs) throws SQLException {
        return new AppealView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getLong(9));
    }

    private static final String SANCTION_COLUMNS =
            "id, uuid, layout, rule, reason, issued_at, expires_at, staff, measure, "
                    + "active, stayed, behaviour_mask, lifted_at, lifted_by, appealable, "
                    + "parent_id, lift_kind";

    private static SanctionView readSanctionView(final ResultSet rs) throws SQLException {
        return new SanctionView(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getString(8),
                rs.getString(9), rs.getInt(10), rs.getInt(11), rs.getLong(12),
                rs.getLong(13), rs.getString(14), rs.getInt(15), rs.getLong(16),
                rs.getInt(17));
    }

    @Override
    public CompletableFuture<List<SanctionView>> listByStaff(final String staff, final int limit,
                                                             final int offset) {
        return scheduler.supply(() -> {
            final List<SanctionView> out = new ArrayList<>(limit);
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT " + SANCTION_COLUMNS + " FROM bans WHERE staff = ? "
                            + "ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, staff);
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(readSanctionView(rs));
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("staff listing failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<SanctionView>> listByRule(final String rule, final int limit,
                                                            final int offset) {
        return scheduler.supply(() -> {
            final List<SanctionView> out = new ArrayList<>(limit);
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT " + SANCTION_COLUMNS + " FROM bans WHERE rule = ? "
                            + "ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, rule);
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(readSanctionView(rs));
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("rule listing failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<SignalRow>> listSignals(final long banId) {
        return scheduler.supply(() -> {
            final List<SignalRow> out = new ArrayList<>();
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT type, value, weight FROM signals WHERE ban_id = ? ORDER BY type")) {
                ps.setLong(1, banId);
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new SignalRow(rs.getInt(1), rs.getString(2), rs.getDouble(3)));
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("signal listing failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<String>> listProvisions(final long banId) {
        return scheduler.supply(() -> {
            final List<String> out = new ArrayList<>();
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT rule FROM provisions WHERE ban_id = ?")) {
                ps.setLong(1, banId);
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("provision listing failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<AuditRow>> listAudit(final Long banId, final int limit,
                                                       final int offset) {
        return scheduler.supply(() -> {
            final List<AuditRow> out = new ArrayList<>(limit);
            final String sql = "SELECT id, ban_id, action, actor, ts, detail FROM audit "
                    + (banId == null ? "" : "WHERE ban_id = ? ")
                    + "ORDER BY id DESC LIMIT ? OFFSET ?";
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                if (banId != null) {
                    ps.setLong(i++, banId);
                }
                ps.setInt(i++, limit);
                ps.setInt(i, offset);
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final long ban = rs.getLong(2);
                        out.add(new AuditRow(rs.getLong(1), rs.wasNull() ? null : ban,
                                rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6)));
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("audit listing failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<SanctionView>> chainNodes(final long anyId) {
        return scheduler.supply(() -> {
            final List<SanctionView> out = new ArrayList<>();
            final Connection c = pool.borrow();
            try {
                long root = anyId;
                try (final PreparedStatement up = c.prepareStatement(
                        "SELECT parent_id FROM bans WHERE id = ?")) {
                    while (true) {
                        up.setLong(1, root);
                        final long parent;
                        try (final ResultSet rs = up.executeQuery()) {
                            if (!rs.next()) {
                                break;
                            }
                            parent = rs.getLong(1);
                        }
                        if (parent == 0L) {
                            break;
                        }
                        root = parent;
                    }
                }
                try (final PreparedStatement ps = c.prepareStatement(
                        "WITH RECURSIVE chain(id) AS ("
                                + "SELECT id FROM bans WHERE id = ? "
                                + "UNION ALL SELECT b.id FROM bans b JOIN chain ON b.parent_id = chain.id) "
                                + "SELECT " + SANCTION_COLUMNS
                                + " FROM bans WHERE id IN (SELECT id FROM chain) ORDER BY id")) {
                    ps.setLong(1, root);
                    try (final ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(readSanctionView(rs));
                        }
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("chain listing failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<StatsRow> stats() {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try (final Statement st = c.createStatement()) {
                int total = 0;
                int active = 0;
                int appeals = 0;
                int rules = 0;
                try (final ResultSet rs = st.executeQuery(
                        "SELECT COUNT(*), SUM(CASE WHEN active = 1 THEN 1 ELSE 0 END) FROM bans")) {
                    if (rs.next()) {
                        total = rs.getInt(1);
                        active = rs.getInt(2);
                    }
                }
                try (final ResultSet rs = st.executeQuery(
                        "SELECT COUNT(*) FROM appeals WHERE status = 'PENDING'")) {
                    if (rs.next()) {
                        appeals = rs.getInt(1);
                    }
                }
                try (final ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM rules_cache")) {
                    if (rs.next()) {
                        rules = rs.getInt(1);
                    }
                }
                return new StatsRow(total, active, appeals, rules);
            } catch (final SQLException e) {
                throw new StorageException("stats query failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<SanctionView>> listRecent(final int limit, final int offset) {
        return scheduler.supply(() -> {
            final List<SanctionView> out = new ArrayList<>(limit);
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT " + SANCTION_COLUMNS + " FROM bans ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ps.setInt(1, limit);
                ps.setInt(2, offset);
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(readSanctionView(rs));
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("recent listing failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<String>> distinctSubjects(final int limit, final int offset) {
        return scheduler.supply(() -> {
            final List<String> out = new ArrayList<>(limit);
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT uuid FROM bans GROUP BY uuid ORDER BY MAX(id) DESC LIMIT ? OFFSET ?")) {
                ps.setInt(1, limit);
                ps.setInt(2, offset);
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("distinct subject listing failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<AppealView>> listAppeals(final int limit, final int offset) {
        return scheduler.supply(() -> {
            final List<AppealView> out = new ArrayList<>(limit);
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT id, ban_id, appellant, text, status, verdict, reviewer, created_at, decided_at "
                            + "FROM appeals ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ps.setInt(1, limit);
                ps.setInt(2, offset);
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(readAppeal(rs));
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("appeal listing failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<AuditRow>> listAuditByAction(final String action, final int limit,
                                                               final int offset) {
        return scheduler.supply(() -> {
            final List<AuditRow> out = new ArrayList<>(limit);
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT id, ban_id, action, actor, ts, detail FROM audit WHERE action = ? "
                            + "ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, action);
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final long ban = rs.getLong(2);
                        out.add(new AuditRow(rs.getLong(1), rs.wasNull() ? null : ban,
                                rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6)));
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("audit-by-action listing failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Void> persistTranscript(final long banId, final long[] stamps,
                                                     final String[] bodies) {
        if (stamps.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        return scheduler.run(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO chat_transcript (ban_id, seq, ts, message) VALUES (?, ?, ?, ?)")) {
                for (int i = 0; i < stamps.length; i++) {
                    ps.setLong(1, banId);
                    ps.setInt(2, i);
                    ps.setLong(3, stamps[i]);
                    ps.setString(4, bodies[i]);
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (final SQLException e) {
                throw new StorageException("transcript persist failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<ChatTranscriptRow>> listTranscript(final long banId) {
        return scheduler.supply(() -> {
            final List<ChatTranscriptRow> out = new ArrayList<>();
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT seq, ts, message FROM chat_transcript WHERE ban_id = ? ORDER BY seq")) {
                ps.setLong(1, banId);
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new ChatTranscriptRow(rs.getInt(1), rs.getLong(2), rs.getString(3)));
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("transcript listing failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Void> persistTrace(final SanctionTraceRow row) {
        return scheduler.run(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO sanction_trace (ban_id, prior_sanctions, prior_warnings, "
                            + "in_probation, escalated, ladder_index, base_ms, final_ms, warn_downgrade) "
                            + "VALUES (?,?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(ban_id) DO UPDATE SET "
                            + "prior_sanctions = excluded.prior_sanctions, "
                            + "prior_warnings = excluded.prior_warnings, "
                            + "in_probation = excluded.in_probation, "
                            + "escalated = excluded.escalated, "
                            + "ladder_index = excluded.ladder_index, "
                            + "base_ms = excluded.base_ms, "
                            + "final_ms = excluded.final_ms, "
                            + "warn_downgrade = excluded.warn_downgrade")) {
                ps.setLong(1, row.banId());
                ps.setInt(2, row.priorSanctions());
                ps.setInt(3, row.priorWarnings());
                ps.setInt(4, row.inProbation() ? 1 : 0);
                ps.setInt(5, row.escalated() ? 1 : 0);
                ps.setInt(6, row.ladderIndex());
                ps.setLong(7, row.baseMs());
                ps.setLong(8, row.finalMs());
                ps.setInt(9, row.warnDowngrade() ? 1 : 0);
                ps.executeUpdate();
            } catch (final SQLException e) {
                throw new StorageException("trace persist failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<SanctionTraceRow> findTrace(final long banId) {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT ban_id, prior_sanctions, prior_warnings, in_probation, escalated, "
                            + "ladder_index, base_ms, final_ms, warn_downgrade "
                            + "FROM sanction_trace WHERE ban_id = ?")) {
                ps.setLong(1, banId);
                try (final ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return new SanctionTraceRow(rs.getLong(1), rs.getInt(2), rs.getInt(3),
                            rs.getInt(4) != 0, rs.getInt(5) != 0, rs.getInt(6),
                            rs.getLong(7), rs.getLong(8), rs.getInt(9) != 0);
                }
            } catch (final SQLException e) {
                throw new StorageException("trace lookup failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<String>> distinctSignalValues(final int type, final int limit,
                                                                final int offset) {
        return scheduler.supply(() -> {
            final List<String> out = new ArrayList<>(limit);
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT DISTINCT value FROM signals WHERE type = ? "
                            + "ORDER BY value LIMIT ? OFFSET ?")) {
                ps.setInt(1, type);
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("distinct signal value listing failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<SanctionView>> sanctionsForSignalValue(final int type,
                                                                         final String value,
                                                                         final int limit,
                                                                         final int offset) {
        return scheduler.supply(() -> {
            final List<SanctionView> out = new ArrayList<>(limit);
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT " + SANCTION_COLUMNS + " FROM bans WHERE id IN "
                            + "(SELECT ban_id FROM signals WHERE type = ? AND value = ?) "
                            + "ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ps.setInt(1, type);
                ps.setString(2, value);
                ps.setInt(3, limit);
                ps.setInt(4, offset);
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(readSanctionView(rs));
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("sanctions-for-signal listing failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<String>> accountsForSignalValue(final int type, final String value) {
        return scheduler.supply(() -> {
            final List<String> out = new ArrayList<>();
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT DISTINCT b.uuid FROM signals s JOIN bans b ON b.id = s.ban_id "
                            + "WHERE s.type = ? AND s.value = ? ORDER BY b.uuid")) {
                ps.setInt(1, type);
                ps.setString(2, value);
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("accounts-for-signal listing failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<String>> coincidentAccounts(final String uuid) {
        return scheduler.supply(() -> {
            final List<String> out = new ArrayList<>();
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT DISTINCT b2.uuid FROM signals s1 "
                            + "JOIN bans b1 ON b1.id = s1.ban_id "
                            + "JOIN signals s2 ON s2.type = s1.type AND s2.value = s1.value "
                            + "JOIN bans b2 ON b2.id = s2.ban_id "
                            + "WHERE b1.uuid = ? AND b2.uuid <> ? ORDER BY b2.uuid")) {
                ps.setString(1, uuid);
                ps.setString(2, uuid);
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("coincident accounts query failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<SignalLinkRow>> listSignalLinks(final int limit) {
        return scheduler.supply(() -> {
            final List<SignalLinkRow> out = new ArrayList<>(Math.min(limit, 4096));
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT s.type, s.value, b.uuid, s.ban_id FROM signals s "
                            + "JOIN bans b ON b.id = s.ban_id "
                            + "ORDER BY s.type, s.value LIMIT ?")) {
                ps.setInt(1, limit);
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new SignalLinkRow(rs.getInt(1), rs.getString(2),
                                rs.getString(3), rs.getLong(4)));
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("signal link listing failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Void> recordUsername(final String uuid, final String name, final long now) {
        if (name == null || name.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return scheduler.run(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO usernames (uuid, name, first_seen, last_seen) VALUES (?,?,?,?) "
                            + "ON CONFLICT(uuid, name) DO UPDATE SET last_seen = excluded.last_seen")) {
                ps.setString(1, uuid);
                ps.setString(2, name);
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.executeUpdate();
            } catch (final SQLException e) {
                throw new StorageException("username record failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<String> resolveName(final String uuid) {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try {
                try (final PreparedStatement ps = c.prepareStatement(
                        "SELECT name FROM usernames WHERE uuid = ? ORDER BY last_seen DESC LIMIT 1")) {
                    ps.setString(1, uuid);
                    try (final ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return rs.getString(1);
                        }
                    }
                }
                try (final PreparedStatement ps = c.prepareStatement(
                        "SELECT subject_name FROM bans WHERE uuid = ? AND subject_name <> '' "
                                + "ORDER BY id DESC LIMIT 1")) {
                    ps.setString(1, uuid);
                    try (final ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return rs.getString(1);
                        }
                    }
                }
                return null;
            } catch (final SQLException e) {
                throw new StorageException("name resolve failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<String> resolveUuidByName(final String name) {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try {
                try (final PreparedStatement ps = c.prepareStatement(
                        "SELECT uuid FROM usernames WHERE name = ? COLLATE NOCASE "
                                + "ORDER BY last_seen DESC LIMIT 1")) {
                    ps.setString(1, name);
                    try (final ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return rs.getString(1);
                        }
                    }
                }
                try (final PreparedStatement ps = c.prepareStatement(
                        "SELECT uuid FROM bans WHERE subject_name = ? COLLATE NOCASE "
                                + "ORDER BY id DESC LIMIT 1")) {
                    ps.setString(1, name);
                    try (final ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return rs.getString(1);
                        }
                    }
                }
                return null;
            } catch (final SQLException e) {
                throw new StorageException("uuid resolve failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<java.util.Map<String, String>> warmNames(final int limit) {
        return scheduler.supply(() -> {
            final java.util.Map<String, String> out = new HashMap<>(1024);
            final Connection c = pool.borrow();
            try {
                try (final PreparedStatement ps = c.prepareStatement(
                        "SELECT uuid, subject_name FROM bans WHERE subject_name <> '' "
                                + "ORDER BY id ASC LIMIT ?")) {
                    ps.setInt(1, limit);
                    try (final ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.put(rs.getString(1), rs.getString(2));
                        }
                    }
                }
                try (final PreparedStatement ps = c.prepareStatement(
                        "SELECT uuid, name FROM usernames ORDER BY last_seen ASC LIMIT ?")) {
                    ps.setInt(1, limit);
                    try (final ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.put(rs.getString(1), rs.getString(2));
                        }
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("name warm failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Void> refreshFrequencyAggregate() {
        return scheduler.run(() -> {
            final Connection c = pool.borrow();
            try {
                c.setAutoCommit(false);
                try (final Statement st = c.createStatement()) {
                    st.executeUpdate("DELETE FROM signal_freq");
                    st.executeUpdate(
                            "INSERT INTO signal_freq (type, value, account_count) "
                                    + "SELECT s.type, s.value, COUNT(DISTINCT b.uuid) "
                                    + "FROM signals s JOIN bans b ON b.id = s.ban_id "
                                    + "GROUP BY s.type, s.value");
                    st.executeUpdate(
                            "UPDATE corpus_stat SET total_accounts = "
                                    + "(SELECT COUNT(DISTINCT uuid) FROM bans) WHERE singleton = 0");
                }
                c.commit();
            } catch (final SQLException e) {
                safeRollback(c);
                throw new StorageException("frequency aggregate refresh failed", e);
            } finally {
                restoreAutoCommit(c);
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<SignalFreqRow>> loadFrequencies(final int limit) {
        return scheduler.supply(() -> {
            final List<SignalFreqRow> out = new ArrayList<>(Math.min(limit, 8192));
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT type, value, account_count FROM signal_freq LIMIT ?")) {
                ps.setInt(1, limit);
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new SignalFreqRow(rs.getInt(1), rs.getString(2), rs.getLong(3)));
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("frequency load failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Long> corpusSize() {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try (final Statement st = c.createStatement();
                 final ResultSet rs = st.executeQuery(
                         "SELECT total_accounts FROM corpus_stat WHERE singleton = 0")) {
                final long recorded = rs.next() ? rs.getLong(1) : 0L;
                return Math.max(recorded, 1L);
            } catch (final SQLException e) {
                throw new StorageException("corpus size query failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Long> openSessionInterval(final String uuid, final String ip, final long at) {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO session_interval (uuid, ip, login_at, logout_at) VALUES (?,?,?,0)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, uuid);
                ps.setString(2, ip);
                ps.setLong(3, at);
                ps.executeUpdate();
                try (final ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : -1L;
                }
            } catch (final SQLException e) {
                throw new StorageException("session interval open failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> closeSessionIntervals(final String uuid, final long at) {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "UPDATE session_interval SET logout_at = ? WHERE uuid = ? AND logout_at = 0")) {
                ps.setLong(1, at);
                ps.setString(2, uuid);
                return ps.executeUpdate();
            } catch (final SQLException e) {
                throw new StorageException("session interval close failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<SessionIntervalRow>> listSessionIntervals(final String uuid,
                                                                            final int limit) {
        return scheduler.supply(() -> {
            final List<SessionIntervalRow> out = new ArrayList<>(Math.min(limit, 512));
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT login_at, logout_at, ip FROM session_interval "
                            + "WHERE uuid = ? ORDER BY login_at DESC LIMIT ?")) {
                ps.setString(1, uuid);
                ps.setInt(2, limit);
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new SessionIntervalRow(rs.getLong(1), rs.getLong(2), rs.getString(3)));
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("session interval listing failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Void> recordActivityHour(final String uuid, final int hour) {
        return scheduler.run(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO activity_hist (uuid, hour, hits) VALUES (?,?,1) "
                            + "ON CONFLICT(uuid, hour) DO UPDATE SET hits = hits + 1")) {
                ps.setString(1, uuid);
                ps.setInt(2, hour);
                ps.executeUpdate();
            } catch (final SQLException e) {
                throw new StorageException("activity hour record failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<long[]> hourHistogram(final String uuid) {
        return scheduler.supply(() -> {
            final long[] hist = new long[24];
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT hour, hits FROM activity_hist WHERE uuid = ?")) {
                ps.setString(1, uuid);
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final int h = rs.getInt(1);
                        if (h >= 0 && h < 24) {
                            hist[h] = rs.getLong(2);
                        }
                    }
                }
                return hist;
            } catch (final SQLException e) {
                throw new StorageException("hour histogram query failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<BeliefRow>> loadBeliefs() {
        return scheduler.supply(() -> {
            final List<BeliefRow> out = new ArrayList<>(16);
            final Connection c = pool.borrow();
            try (final Statement st = c.createStatement();
                 final ResultSet rs = st.executeQuery(
                         "SELECT type, alpha, beta, updated_at FROM signal_belief")) {
                while (rs.next()) {
                    out.add(new BeliefRow(rs.getInt(1), rs.getDouble(2),
                            rs.getDouble(3), rs.getLong(4)));
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("belief load failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Void> saveBelief(final int type, final double alpha,
                                              final double beta, final long now) {
        return scheduler.run(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO signal_belief (type, alpha, beta, updated_at) VALUES (?,?,?,?) "
                            + "ON CONFLICT(type) DO UPDATE SET alpha = excluded.alpha, "
                            + "beta = excluded.beta, updated_at = excluded.updated_at")) {
                ps.setInt(1, type);
                ps.setDouble(2, alpha);
                ps.setDouble(3, beta);
                ps.setLong(4, now);
                ps.executeUpdate();
            } catch (final SQLException e) {
                throw new StorageException("belief save failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<org.synergyst.minutiae.fingerprint.MatchingSignalRow>>
    matchingSignals(final int[] types, final String[] values, final long now) {
        if (types.length == 0) {
            return CompletableFuture.completedFuture(List.of());
        }
        return scheduler.supply(() -> {
            // Build a disjunction of (type, value) equality predicates, one per
            // probe signal, so a single indexed query returns every agreement.
            final StringBuilder sql = new StringBuilder(320);
            sql.append("SELECT b.uuid, s.type, s.value, b.issued_at ")
                    .append("FROM signals s JOIN bans b ON b.id = s.ban_id ")
                    .append("WHERE b.active = 1 AND b.stayed = 0 ")
                    .append("AND b.measure IN ('SUSPENSION','CUSTODY') ")
                    .append("AND (b.expires_at = 0 OR b.expires_at > ?) AND (");
            for (int i = 0; i < types.length; i++) {
                if (i > 0) {
                    sql.append(" OR ");
                }
                sql.append("(s.type = ? AND s.value = ?)");
            }
            sql.append(")");

            final List<org.synergyst.minutiae.fingerprint.MatchingSignalRow> out = new ArrayList<>();
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(sql.toString())) {
                int idx = 1;
                ps.setLong(idx++, now);
                for (int i = 0; i < types.length; i++) {
                    ps.setInt(idx++, types[i]);
                    ps.setString(idx++, values[i]);
                }
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new org.synergyst.minutiae.fingerprint.MatchingSignalRow(
                                rs.getString(1), rs.getInt(2), rs.getString(3), rs.getLong(4)));
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("matching-signal query failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Long> publishDirective(final String origin, final String target,
                                                    final String kind, final String subject,
                                                    final String payload, final long now,
                                                    final long ttlMs) {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO directives (origin, target, kind, subject, payload,"
                            + " created_at, expires_at) VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, origin);
                ps.setString(2, target);
                ps.setString(3, kind);
                ps.setString(4, subject);
                ps.setString(5, payload);
                ps.setLong(6, now);
                ps.setLong(7, now + ttlMs);
                ps.executeUpdate();
                try (final ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : -1L;
                }
            } catch (final SQLException e) {
                throw new StorageException("directive publish failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<DirectiveRow>> pollDirectives(final String serverId,
                                                                final long afterId,
                                                                final long now, final int limit) {
        return scheduler.supply(() -> {
            final List<DirectiveRow> out = new ArrayList<>(Math.min(limit, 64));
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT id, origin, target, kind, subject, payload, created_at, expires_at"
                            + " FROM directives WHERE id > ? AND expires_at > ?"
                            + " AND ((target IS NULL AND origin <> ?) OR target = ?)"
                            + " ORDER BY id ASC LIMIT ?")) {
                ps.setLong(1, afterId);
                ps.setLong(2, now);
                ps.setString(3, serverId);
                ps.setString(4, serverId);
                ps.setInt(5, limit);
                try (final ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new DirectiveRow(rs.getLong(1), rs.getString(2),
                                rs.getString(3), rs.getString(4), rs.getString(5),
                                rs.getString(6), rs.getLong(7), rs.getLong(8)));
                    }
                }
                return out;
            } catch (final SQLException e) {
                throw new StorageException("directive poll failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Long> directiveCursor(final String serverId) {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "SELECT last_id FROM directive_cursor WHERE server_id = ?")) {
                ps.setString(1, serverId);
                try (final ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            } catch (final SQLException e) {
                throw new StorageException("directive cursor read failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Void> saveDirectiveCursor(final String serverId, final long lastId) {
        return scheduler.run(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO directive_cursor (server_id, last_id) VALUES (?, ?)"
                            + " ON CONFLICT(server_id) DO UPDATE SET last_id = excluded.last_id")) {
                ps.setString(1, serverId);
                ps.setLong(2, lastId);
                ps.executeUpdate();
            } catch (final SQLException e) {
                throw new StorageException("directive cursor write failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Long> maxDirectiveId() {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try (final Statement st = c.createStatement();
                 final ResultSet rs = st.executeQuery(
                         "SELECT COALESCE(MAX(id), 0) FROM directives")) {
                return rs.next() ? rs.getLong(1) : 0L;
            } catch (final SQLException e) {
                throw new StorageException("directive max-id query failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> purgeDirectives(final long now) {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM directives WHERE expires_at <= ?")) {
                ps.setLong(1, now);
                return ps.executeUpdate();
            } catch (final SQLException e) {
                throw new StorageException("directive purge failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> activateSuspended(final String uuid, final String rule,
                                                        final long now) {
        if (rule == null) {
            return CompletableFuture.completedFuture(0);
        }
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "UPDATE bans SET active = 1, suspended = 0 "
                            + "WHERE uuid = ? AND rule = ? AND suspended = 1 "
                            + "AND suspend_until > ? AND measure <> 'WARN'")) {
                ps.setString(1, uuid);
                ps.setString(2, rule);
                ps.setLong(3, now);
                return ps.executeUpdate();
            } catch (final SQLException e) {
                throw new StorageException("suspended activation failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<Long> addCaseNote(final Long banId, final String uuid,
                                               final String author, final String text,
                                               final long ts) {
        return scheduler.supply(() -> {
            final Connection c = pool.borrow();
            try (final PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO case_notes (ban_id, uuid, author, text, ts) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                if (banId == null) {
                    ps.setNull(1, java.sql.Types.INTEGER);
                } else {
                    ps.setLong(1, banId);
                }
                ps.setString(2, uuid);
                ps.setString(3, author);
                ps.setString(4, text);
                ps.setLong(5, ts);
                ps.executeUpdate();
                try (final ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : -1L;
                }
            } catch (final SQLException e) {
                throw new StorageException("case note insert failed", e);
            } finally {
                pool.release(c);
            }
        });
    }

    @Override
    public CompletableFuture<List<SanctionView>> claimReviewDue(final long now, final int limit) {
        return scheduler.supply(() -> {
            final List<SanctionView> out = new ArrayList<>(limit);
            final Connection c = pool.borrow();
            try {
                c.setAutoCommit(false);
                try (final PreparedStatement ps = c.prepareStatement(
                        "SELECT " + SANCTION_COLUMNS + " FROM bans "
                                + "WHERE review_at > 0 AND review_at <= ? "
                                + "ORDER BY review_at ASC LIMIT ?")) {
                    ps.setLong(1, now);
                    ps.setInt(2, limit);
                    try (final ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(readSanctionView(rs));
                        }
                    }
                }
                if (!out.isEmpty()) {
                    try (final PreparedStatement clear = c.prepareStatement(
                            "UPDATE bans SET review_at = 0 WHERE id = ?")) {
                        for (final SanctionView v : out) {
                            clear.setLong(1, v.id());
                            clear.addBatch();
                        }
                        clear.executeBatch();
                    }
                }
                c.commit();
                return out;
            } catch (final SQLException e) {
                safeRollback(c);
                throw new StorageException("review claim failed", e);
            } finally {
                restoreAutoCommit(c);
                pool.release(c);
            }
        });
    }


    // ----------------------------------------------------------------------
    // Transaction and lifecycle helpers
    // ----------------------------------------------------------------------

    /**
     * Rolls back a connection, logging but suppressing any failure so that the
     * originating exception is the one propagated to the caller.
     *
     * @param connection the connection to roll back
     */
    private void safeRollback(final Connection connection) {
        try {
            connection.rollback();
        } catch (final SQLException e) {
            log.error("storage", e, "rollback failed");
        }
    }

    /**
     * Restores autocommit on a connection, logging but suppressing any failure.
     * Invoked from the {@code finally} block of every transactional operation so
     * that a connection returned to the pool is not left mid-transaction.
     *
     * @param connection the connection to restore
     */
    private void restoreAutoCommit(final Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (final SQLException e) {
            log.warn("storage", "failed to restore autocommit: %s", e.getMessage());
        }
    }

    /**
     * Resolves the database file path, creating parent directories as needed.
     *
     * @return the resolved database path
     */
    private Path resolveDatabasePath() {
        final Path candidate = Path.of(config.sqliteFile());
        final Path resolved = candidate.isAbsolute() ? candidate : dataFolder.resolve(candidate);
        try {
            Files.createDirectories(resolved.toAbsolutePath().getParent());
        } catch (final IOException e) {
            throw new StorageException("failed to create database directory", e);
        }
        return resolved;
    }

    /**
     * Loads the SQLite JDBC driver explicitly.
     *
     * @throws StorageException if the driver is not present on the classpath
     */
    private void loadDriver() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (final ClassNotFoundException e) {
            throw new StorageException("SQLite JDBC driver not present on classpath", e);
        }
    }
}