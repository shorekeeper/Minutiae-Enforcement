package org.synergyst.minutiae.name;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.synergyst.minutiae.lifecycle.LifecycleComponent;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.storage.Storage;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative account-name resolver backed by an in-memory cache.
 *
 * <p>The service maintains a concurrent map from account UUID to display name.
 * On boot the map is warmed in a single query from the persisted username
 * history and sanction subject names, so a name known to storage is resolvable
 * without a per-lookup round trip. The map is thereafter updated on player join,
 * which observes the current name, and by the sanction executor, which observes
 * the target name at issue time; both observations are also persisted to the
 * username history for durability across restarts.
 *
 * <p>Resolution is a pure cache read. A miss returns the UUID string and, once
 * per outstanding UUID, schedules an asynchronous fill from storage so that a
 * subsequent read reflects the resolved name. The fill is non-blocking and
 * self-limiting through a pending-set guard, so a burst of misses on the same
 * UUID issues a single query. The service performs no blocking storage access on
 * any resolution path.
 *
 * <p>The backing structures are concurrent; the service is safe for use from any
 * thread, including the web request pool and the asynchronous storage scheduler.
 */
public final class NameService implements LifecycleComponent, Listener {

    private static final int WARM_LIMIT = 100_000;

    private final KernelLogger log;
    private final Storage storage;
    private final ConcurrentHashMap<UUID, String> cache = new ConcurrentHashMap<>(1024);
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    public NameService(final KernelLogger log, final Storage storage) {
        this.log = log;
        this.storage = storage;
    }

    @Override
    public String tag() {
        return "names";
    }

    @Override
    public void boot() {
        final Map<String, String> warm = storage.warmNames(WARM_LIMIT).join();
        for (final Map.Entry<String, String> e : warm.entrySet()) {
            try {
                cache.put(UUID.fromString(e.getKey()), e.getValue());
            } catch (final IllegalArgumentException ignored) {
                // Malformed UUID string in storage; skipped.
            }
        }
        log.info("names", "resolver warmed with %d name(s)", cache.size());
    }

    @Override
    public void shutdown() {
        cache.clear();
        pending.clear();
    }

    /**
     * Records a name observation for an account, updating the cache and
     * persisting the observation.
     *
     * @param uuid the account UUID
     * @param name the observed name
     */
    public void record(final UUID uuid, final String name) {
        if (uuid == null || name == null || name.isEmpty()) {
            return;
        }
        cache.put(uuid, name);
        storage.recordUsername(uuid.toString(), name, System.currentTimeMillis())
                .exceptionally(err -> {
                    log.warn("names", "username persist failed for %s: %s", uuid, err.getMessage());
                    return null;
                });
    }

    /**
     * Resolves the display name for an account without blocking.
     *
     * @param uuid the account UUID, possibly null
     * @return the cached name, or the UUID string on a miss; an asynchronous
     *         fill is scheduled on a miss
     */
    public String name(final UUID uuid) {
        if (uuid == null) {
            return "unknown";
        }
        final String cached = cache.get(uuid);
        if (cached != null) {
            return cached;
        }
        fillAsync(uuid);
        return uuid.toString();
    }

    private void fillAsync(final UUID uuid) {
        if (!pending.add(uuid)) {
            return;
        }
        storage.resolveName(uuid.toString()).whenComplete((resolved, err) -> {
            pending.remove(uuid);
            if (err == null && resolved != null && !resolved.isEmpty()) {
                cache.put(uuid, resolved);
            }
        });
    }

    /**
     * Records the joining player's current name.
     *
     * @param event the join event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        record(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }
}