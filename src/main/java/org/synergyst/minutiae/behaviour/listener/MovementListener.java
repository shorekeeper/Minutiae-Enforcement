package org.synergyst.minutiae.behaviour.listener;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.synergyst.minutiae.behaviour.Behaviour;
import org.synergyst.minutiae.behaviour.BehaviourConfig;
import org.synergyst.minutiae.behaviour.BehaviourEffects;
import org.synergyst.minutiae.behaviour.BehaviourManager;
import org.synergyst.minutiae.behaviour.BehaviourRecord;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces the movement consequences of the rubberband and quarantine
 * constraints.
 *
 * <p>This is the most frequently invoked listener; it is guarded aggressively.
 * It returns immediately when no behavioural state exists, when the moving
 * player has no state, and when the move did not cross a block boundary. Only
 * after those guards does it evaluate constraints. Rubberband reverts any move
 * whose horizontal-plus-vertical displacement exceeds the configured leash to
 * its origin. Quarantine confines the player within the anchor radius, pulling
 * an out-of-bounds move back to the anchor and issuing a rate-limited boundary
 * notice.
 *
 * <p>All work is main-thread and allocation-free on the common path; distance
 * comparisons use squared magnitudes to avoid square roots.
 */
public final class MovementListener implements Listener {

    private static final long BOUNDARY_NOTICE_INTERVAL_MS = 3_000L;

    private final BehaviourManager manager;
    private final BehaviourConfig config;
    private final BehaviourEffects effects;

    private final ConcurrentHashMap<UUID, Long> lastBoundaryNotice = new ConcurrentHashMap<>(16);

    private final double leashSquared;
    private final double radiusSquared;

    public MovementListener(final BehaviourManager manager,
                            final BehaviourConfig config,
                            final BehaviourEffects effects) {
        this.manager = manager;
        this.config = config;
        this.effects = effects;
        this.leashSquared = config.rubberbandLeash() * config.rubberbandLeash();
        this.radiusSquared = config.quarantineRadius() * config.quarantineRadius();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        if (manager.isEmpty() || !event.hasChangedBlock()) {
            return;
        }
        final Player player = event.getPlayer();
        final BehaviourRecord record = manager.get(player.getUniqueId());
        if (record == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        final boolean rubberband = record.has(Behaviour.RUBBERBAND, now);
        final boolean quarantined = record.has(Behaviour.QUARANTINED, now);
        if (!rubberband && !quarantined) {
            return;
        }

        final Location from = event.getFrom();
        final Location to = event.getTo();

        if (rubberband) {
            final double dx = to.getX() - from.getX();
            final double dy = to.getY() - from.getY();
            final double dz = to.getZ() - from.getZ();
            if (dx * dx + dy * dy + dz * dz > leashSquared) {
                event.setTo(from);
                return;
            }
        }

        if (quarantined) {
            final Location anchor = effects.anchor();
            if (!to.getWorld().equals(anchor.getWorld())) {
                effects.confine(player);
                return;
            }
            final double dx = to.getX() - anchor.getX();
            final double dy = to.getY() - anchor.getY();
            final double dz = to.getZ() - anchor.getZ();
            if (dx * dx + dy * dy + dz * dz > radiusSquared) {
                final Location back = anchor.clone();
                back.setYaw(to.getYaw());
                back.setPitch(to.getPitch());
                event.setTo(back);
                maybeNoticeBoundary(player, now);
            }
        }
    }

    private void maybeNoticeBoundary(final Player player, final long now) {
        final Long last = lastBoundaryNotice.get(player.getUniqueId());
        if (last == null || now - last >= BOUNDARY_NOTICE_INTERVAL_MS) {
            lastBoundaryNotice.put(player.getUniqueId(), now);
            effects.boundaryNotice(player);
        }
    }
}