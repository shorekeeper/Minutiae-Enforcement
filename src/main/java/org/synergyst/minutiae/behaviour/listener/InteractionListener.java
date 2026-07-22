package org.synergyst.minutiae.behaviour.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.synergyst.minutiae.behaviour.Behaviour;
import org.synergyst.minutiae.behaviour.BehaviourManager;

import java.util.UUID;

/**
 * Voids world interactions for concealed players.
 *
 * <p>A player who is ghosted or shadowed cannot break or place blocks, interact
 * with blocks or entities, or deal entity damage. Cancellation is silent for
 * both constraints: a ghost is a staff observation tool, and a shadow must not
 * reveal itself. Each handler is guarded by the store-emptiness check and a
 * single concealment lookup.
 *
 * <p>All handlers run on the main thread and touch only in-memory state.
 */
public final class InteractionListener implements Listener {

    private final BehaviourManager manager;

    public InteractionListener(final BehaviourManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        if (concealed(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        if (concealed(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        if (concealed(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        if (concealed(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(final EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && concealed(player)) {
            event.setCancelled(true);
        }
    }

    private boolean concealed(final Player player) {
        if (manager.isEmpty()) {
            return false;
        }
        return manager.isConcealed(player.getUniqueId(), System.currentTimeMillis());
    }
}