package org.synergyst.minutiae.preference;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.storage.Storage;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of per-staff report preferences, backed by persistent storage.
 *
 * <p>The single tracked preference is verbosity of the executor's own report:
 * verbose recipients see a multi-line breakdown, others a compact one-line
 * summary. The cache is populated asynchronously on join and cleared on quit;
 * a write-through updates both cache and storage. Reads are synchronous and
 * lock-free, defaulting to verbose when no entry is cached, which covers the
 * console sender and any player whose asynchronous load has not yet completed.
 */
public final class PreferenceService implements Listener {

    private final KernelLogger log;
    private final Storage storage;
    private final ConcurrentHashMap<UUID, Boolean> verbose = new ConcurrentHashMap<>(64);

    public PreferenceService(final KernelLogger log, final Storage storage) {
        this.log = log;
        this.storage = storage;
    }

    /**
     * Loads a joining player's preference into the cache.
     *
     * @param event the join event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final UUID uuid = event.getPlayer().getUniqueId();
        storage.getVerbose(uuid.toString()).whenComplete((value, err) -> {
            if (err != null) {
                log.error("preference", err, "verbose load failed for %s", event.getPlayer().getName());
                return;
            }
            verbose.put(uuid, value);
        });
    }

    /**
     * Evicts a quitting player's cached preference.
     *
     * @param event the quit event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        verbose.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Reports whether a sender receives verbose reports.
     *
     * @param sender the sender
     * @return {@code true} for the console, or for a player whose cached
     *         preference is verbose or absent
     */
    public boolean isVerbose(final CommandSender sender) {
        if (sender instanceof Player player) {
            return verbose.getOrDefault(player.getUniqueId(), Boolean.TRUE);
        }
        return true;
    }

    /**
     * Sets a player's verbose preference, updating cache and storage.
     *
     * @param player the player
     * @param value  the preference
     */
    public void setVerbose(final Player player, final boolean value) {
        verbose.put(player.getUniqueId(), value);
        storage.setVerbose(player.getUniqueId().toString(), value);
    }
}