package org.synergyst.minutiae.behaviour.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.synergyst.minutiae.behaviour.BehaviourEffects;
import org.synergyst.minutiae.behaviour.BehaviourManager;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.storage.BehaviourRow;
import org.synergyst.minutiae.storage.Storage;

import java.util.List;
import java.util.UUID;

/**
 * Reconciles behavioural state across the connection lifecycle.
 *
 * <p>On join, the player's active behavioural sanctions are queried
 * asynchronously and folded into the in-memory store, after which their effects
 * are applied on the main thread. Concealment of already-online concealed
 * players is additionally reconciled toward the joining player, so a ghost or
 * shadow present before the join remains hidden from the newcomer. On quit, the
 * player's in-memory state is discarded; residual per-viewer concealment is
 * cleaned up by the platform as the player leaves.
 *
 * <p>The join query joins the storage stage on completion and marshals all
 * entity-touching work back to the main thread.
 */
public final class PresenceListener implements Listener {

    private final KernelLogger log;
    private final JavaPlugin plugin;
    private final Storage storage;
    private final BehaviourManager manager;
    private final BehaviourEffects effects;

    public PresenceListener(final KernelLogger log,
                            final JavaPlugin plugin,
                            final Storage storage,
                            final BehaviourManager manager,
                            final BehaviourEffects effects) {
        this.log = log;
        this.plugin = plugin;
        this.storage = storage;
        this.manager = manager;
        this.effects = effects;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        final long now = System.currentTimeMillis();

        // Reconcile the newcomer's view of already-concealed players immediately;
        // this requires no storage access.
        effects.hideConcealedFrom(player, now);

        storage.activeBehaviours(uuid.toString(), now).whenComplete((rows, err) -> {
            if (err != null) {
                log.error("behaviour", err, "behaviour load failed for %s", player.getName());
                return;
            }
            if (rows.isEmpty()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (final BehaviourRow row : rows) {
                    manager.apply(uuid, row.behaviourMask(), row.expiresAt(), row.reason());
                }
                if (player.isOnline()) {
                    effects.apply(player, System.currentTimeMillis());
                }
            });
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        manager.remove(event.getPlayer().getUniqueId());
    }
}