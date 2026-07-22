package org.synergyst.minutiae.storage.migration;

import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.storage.StorageException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Applies pending schema migrations transactionally.
 *
 * <p>The runner reads the currently applied version from {@code schema_version},
 * then applies each catalogued migration whose version exceeds it, in ascending
 * order. Each migration executes within a single transaction; a failure rolls
 * that migration back and aborts the run without advancing the version marker,
 * leaving the database in its last consistent state.
 *
 * <p>The runner is stateless and holds no connection; a connection is supplied
 * per invocation.
 */
public final class MigrationRunner {

    private final KernelLogger log;

    public MigrationRunner(final KernelLogger log) {
        this.log = log;
    }

    /**
     * Migrates the schema to the catalogue target version.
     *
     * @param connection an open, writable connection with autocommit enabled
     * @return the schema version after migration completes
     * @throws StorageException if any migration fails
     */
    public int migrate(final Connection connection) {
        try {
            ensureBaseline(connection);
            final int current = readVersion(connection);
            final int target = MigrationCatalog.targetVersion();

            if (current >= target) {
                log.info("schema", "up to date at version %d (target %d)", current, target);
                return current;
            }

            log.info("schema", "migrating from version %d to %d", current, target);
            int applied = current;
            for (final Migration migration : MigrationCatalog.migrations()) {
                if (migration.version() <= current) {
                    continue;
                }
                applyOne(connection, migration);
                applied = migration.version();
            }
            log.info("schema", "migration complete; schema at version %d", applied);
            return applied;
        } catch (final SQLException e) {
            throw new StorageException("schema migration failed", e);
        }
    }

    private void ensureBaseline(final Connection connection) throws SQLException {
        // The baseline migration is self-bootstrapping: it creates the version
        // table it depends upon. If no schema_version table exists yet the read
        // below returns 0, which selects the baseline migration for execution.
        try (final Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                        singleton INTEGER PRIMARY KEY CHECK (singleton = 0),
                        version   INTEGER NOT NULL
                    )
                    """);
            st.execute("INSERT OR IGNORE INTO schema_version (singleton, version) VALUES (0, 0)");
        }
    }

    private int readVersion(final Connection connection) throws SQLException {
        try (final Statement st = connection.createStatement();
             final var rs = st.executeQuery("SELECT version FROM schema_version WHERE singleton = 0")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void applyOne(final Connection connection, final Migration migration) throws SQLException {
        log.trace("schema", "applying v%d '%s' (%d statement(s))",
                migration.version(), migration.description(), migration.statements().length);

        final boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (final Statement st = connection.createStatement()) {
            for (final String sql : migration.statements()) {
                st.execute(sql);
            }
            try (final Statement mark = connection.createStatement()) {
                mark.executeUpdate(
                        "UPDATE schema_version SET version = " + migration.version() + " WHERE singleton = 0");
            }
            connection.commit();
            log.trace("schema", "v%d committed", migration.version());
        } catch (final SQLException e) {
            safeRollback(connection);
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void safeRollback(final Connection connection) {
        try {
            connection.rollback();
        } catch (final SQLException rollbackFailure) {
            log.error("schema", rollbackFailure, "rollback failed");
        }
    }
}