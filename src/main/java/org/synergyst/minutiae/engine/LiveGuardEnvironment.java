package org.synergyst.minutiae.engine;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.synergyst.minutiae.fingerprint.FingerprintService;
import org.synergyst.minutiae.storage.Storage;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-blocking guard environment for live dispatch.
 *
 * <p>Guard getters are evaluated inline during event dispatch, which for
 * several event kinds runs on the server main thread. Every storage- and
 * service-backed getter therefore reads only from a bounded in-memory cache
 * and performs no I/O on the calling thread. A cache miss returns a neutral
 * value and schedules an off-thread fill guarded by a pending-key set, so that
 * a subsequent evaluation observes the resolved value while a burst of misses
 * on one key issues a single query. Entries carry a fixed time-to-live after
 * which the next access schedules a refresh, the stale value being served
 * meanwhile. An unresolved getter yields a neutral value that fails to satisfy
 * its containing comparison, consistent with the fail-safe posture of the
 * engine.
 */
public final class LiveGuardEnvironment implements GuardEnvironment {

    private static final long TTL_MS = 30_000L;

    private record Entry(double value, long expiresAt) {
    }

    private final Storage storage;
    private final FingerprintService fingerprint;
    private final JavaPlugin plugin;

    private final Map<String, Entry> precedentCache = new ConcurrentHashMap<>(256);
    private final Set<String> precedentPending = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Entry> scoreCache = new ConcurrentHashMap<>(256);
    private final Set<UUID> scorePending = ConcurrentHashMap.newKeySet();

    public LiveGuardEnvironment(final Storage storage,
                                final FingerprintService fingerprint,
                                final JavaPlugin plugin) {
        this.storage = storage;
        this.fingerprint = fingerprint;
        this.plugin = plugin;
    }

    @Override
    public long precedent(final UUID subject, final String rule) {
        if (subject == null || rule == null) {
            return 0L;
        }
        final String key = subject + "|" + rule;
        final long now = now();
        final Entry e = precedentCache.get(key);
        if (e == null || e.expiresAt() <= now) {
            fillPrecedent(key, subject, rule, now);
        }
        return e == null ? 0L : (long) e.value();
    }

    @Override
    public double fingerprintScore(final UUID subject) {
        if (subject == null) {
            return 0.0d;
        }
        final long now = now();
        final Entry e = scoreCache.get(subject);
        if (e == null || e.expiresAt() <= now) {
            fillScore(subject, now);
        }
        return e == null ? 0.0d : e.value();
    }

    @Override
    public boolean isOnline(final UUID subject) {
        return subject != null && plugin.getServer().getPlayer(subject) != null;
    }

    @Override
    public long now() {
        return System.currentTimeMillis();
    }

    private void fillPrecedent(final String key, final UUID subject, final String rule, final long now) {
        if (!precedentPending.add(key)) {
            return;
        }
        storage.precedent(subject.toString(), rule, now).whenComplete((p, err) -> {
            if (err == null && p != null) {
                precedentCache.put(key, new Entry(p.priorSanctions(), now + TTL_MS));
            }
            precedentPending.remove(key);
        });
    }

    private void fillScore(final UUID subject, final long now) {
        if (!scorePending.add(subject)) {
            return;
        }
        // Resolve the player on the calling thread, as the scoring probe reads
        // the live player's address and name; storage access within scorePlayer
        // is itself asynchronous.
        final Player player = plugin.getServer().getPlayer(subject);
        if (player == null) {
            scoreCache.put(subject, new Entry(0.0d, now + TTL_MS));
            scorePending.remove(subject);
            return;
        }
        fingerprint.scorePlayer(player).whenComplete((match, err) -> {
            final double score = (err != null || match == null) ? 0.0d : match.score();
            scoreCache.put(subject, new Entry(score, now + TTL_MS));
            scorePending.remove(subject);
        });
    }
}