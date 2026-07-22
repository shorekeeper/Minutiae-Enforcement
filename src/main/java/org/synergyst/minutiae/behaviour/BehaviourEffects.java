package org.synergyst.minutiae.behaviour;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.message.MessageKey;
import org.synergyst.minutiae.message.MessageService;
import org.synergyst.minutiae.message.Ph;

/**
 * Applies the Bukkit-side effects of behavioural constraints: confinement
 * teleport, cross-player concealment, and player notification.
 *
 * <p>All methods here must run on the server main thread, since they touch
 * entities and world state. The quarantine anchor is resolved lazily and cached
 * on first use; a configured world that is not loaded falls back to the primary
 * world with a single logged warning.
 *
 * <p>Concealment is bidirectional and applied per viewer pair. Applying to a
 * newly-concealed player hides them from every current online player. Applying
 * on a fresh join additionally hides every already-concealed player from the
 * joining player, so concealment holds regardless of connection order.
 */
public final class BehaviourEffects {

    private final KernelLogger log;
    private final JavaPlugin plugin;
    private final BehaviourConfig config;
    private final BehaviourManager manager;
    private final MessageService messages;

    private Location anchorCache;

    public BehaviourEffects(final KernelLogger log,
                            final JavaPlugin plugin,
                            final BehaviourConfig config,
                            final BehaviourManager manager,
                            final MessageService messages) {
        this.log = log;
        this.plugin = plugin;
        this.config = config;
        this.manager = manager;
        this.messages = messages;
    }

    /**
     * Resolves the quarantine anchor location, caching the result.
     *
     * @return the anchor location
     */
    public Location anchor() {
        if (anchorCache != null) {
            return anchorCache;
        }
        World world = plugin.getServer().getWorld(config.quarantineWorld());
        if (world == null) {
            world = plugin.getServer().getWorlds().get(0);
            log.warn("behaviour", "quarantine world '%s' not loaded; using '%s'",
                    config.quarantineWorld(), world.getName());
        }
        anchorCache = new Location(world, config.quarantineX(), config.quarantineY(),
                config.quarantineZ());
        return anchorCache;
    }

    /**
     * Applies the full effect of a player's current behavioural record.
     *
     * @param player the online player
     * @param now    current epoch-millisecond timestamp
     */
    public void apply(final Player player, final long now) {
        final BehaviourRecord record = manager.get(player.getUniqueId());
        if (record == null) {
            return;
        }
        if (record.has(Behaviour.QUARANTINED, now)) {
            confine(player);
        }
        if (record.has(Behaviour.GHOSTED, now) || record.has(Behaviour.SHADOWED, now)) {
            concealFromAll(player);
        }
    }

    /**
     * Teleports a player to the quarantine anchor, preserving orientation, and
     * notifies them.
     *
     * @param player the player to confine
     */
    public void confine(final Player player) {
        final Location target = anchor().clone();
        target.setYaw(player.getLocation().getYaw());
        target.setPitch(player.getLocation().getPitch());
        player.teleport(target);
        messages.send(player, MessageKey.BEHAVIOUR_QUARANTINE_APPLIED);
    }

    /**
     * Hides a concealed player from every other online player.
     *
     * @param concealed the concealed player
     */
    public void concealFromAll(final Player concealed) {
        for (final Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (!viewer.equals(concealed)) {
                viewer.hidePlayer(plugin, concealed);
            }
        }
    }

    /**
     * Reconciles concealment for a freshly-joined viewer against all currently
     * concealed players, hiding each from the viewer.
     *
     * @param viewer the joining player
     * @param now    current epoch-millisecond timestamp
     */
    public void hideConcealedFrom(final Player viewer, final long now) {
        for (final Player other : plugin.getServer().getOnlinePlayers()) {
            if (!other.equals(viewer) && manager.isConcealed(other.getUniqueId(), now)) {
                viewer.hidePlayer(plugin, other);
            }
        }
    }

    private void notifyBoundary(final Player player) {
        messages.send(player, MessageKey.BEHAVIOUR_QUARANTINE_BOUNDARY);
    }

    /**
     * Reconciles a player's cross-player concealment against their current
     * behavioural record. A player who should be concealed is hidden from all
     * others; a player who should not is revealed to all others. This is the
     * reversal path used when a concealing sanction is lifted.
     *
     * @param player the player to reconcile
     * @param now    current epoch-millisecond timestamp
     */
    public void reconcileConcealment(final Player player, final long now) {
        if (manager.isConcealed(player.getUniqueId(), now)) {
            concealFromAll(player);
        } else {
            showToAll(player);
        }
    }

    /**
     * Reveals a previously-concealed player to every other online player.
     *
     * @param revealed the player to reveal
     */
    public void showToAll(final Player revealed) {
        for (final Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (!viewer.equals(revealed)) {
                viewer.showPlayer(plugin, revealed);
            }
        }
    }

    /**
     * Sends the quarantine boundary notice to a player.
     *
     * @param player the player who reached the boundary
     */
    public void boundaryNotice(final Player player) {
        notifyBoundary(player);
    }
}