package org.synergyst.minutiae.fingerprint;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.storage.Storage;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Records the temporal audit trail that backs the session-correlation heuristic.
 *
 * <p>On join, an open session interval is created for the player and the join
 * hour is folded into the player's hour-of-day activity histogram. On quit, every
 * open interval for the player is closed at the current time. These records feed
 * the anti-cooccurrence, login-velocity, and active-hour observations used to
 * distinguish an alternate account from a co-located distinct person.
 *
 * <p>All persistence is dispatched through the asynchronous storage scheduler; the
 * handlers touch no blocking call on the main thread. Interval bookkeeping is
 * idempotent under an unclean shutdown: an interval left open by a crash is
 * treated by every consumer as ending at the current time and is closed on the
 * player's next quit.
 */
public final class SessionAuditListener implements Listener {

    private final KernelLogger log;
    private final Storage storage;

    public SessionAuditListener(final KernelLogger log, final Storage storage) {
        this.log = log;
        this.storage = storage;
    }

    /**
     * Opens a session interval and records the join hour.
     *
     * @param event the join event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final String uuid = player.getUniqueId().toString();
        final long now = System.currentTimeMillis();

        // Capture the login address string, mirroring the fingerprint session so
        // that handoff detection can constrain transitions to a shared address.
        final String ip = player.getAddress() != null && player.getAddress().getAddress() != null
                ? player.getAddress().getAddress().getHostAddress() : null;

        storage.openSessionInterval(uuid, ip, now)
                .exceptionally(err -> {
                    log.warn("fingerprint", "session open failed for %s: %s",
                            player.getName(), err.getMessage());
                    return -1L;
                });

        // Bin the observation by local hour of day. The server zone is used, so
        // the histogram reflects the operator's playing schedule as the server
        // observes it, which is the frame in which two accounts are compared.
        final int hour = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).getHour();
        storage.recordActivityHour(uuid, hour)
                .exceptionally(err -> null);
    }

    /**
     * Closes every open session interval for the departing player.
     *
     * @param event the quit event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        storage.closeSessionIntervals(event.getPlayer().getUniqueId().toString(),
                        System.currentTimeMillis())
                .exceptionally(err -> {
                    log.warn("fingerprint", "session close failed for %s: %s",
                            event.getPlayer().getName(), err.getMessage());
                    return 0;
                });
    }
}