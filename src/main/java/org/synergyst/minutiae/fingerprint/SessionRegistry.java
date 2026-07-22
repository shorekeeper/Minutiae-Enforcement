package org.synergyst.minutiae.fingerprint;

import com.destroystokyo.paper.event.player.PlayerClientOptionsChangeEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of live player sessions, keyed by UUID.
 *
 * <p>The registry observes the connection lifecycle at three points. At
 * pre-login, on the asynchronous connection thread, it records the remote
 * address and name at the earliest possible point. At join, on the main thread,
 * it enriches the session with the now-available locale, client brand, protocol
 * version, render distance, and main hand. On each client-options change,
 * including the initial settings packet sent during login, it refreshes locale,
 * render distance, main hand, and the enabled skin-part bitmask. Entries persist
 * after quit so that a sanction issued shortly after disconnect can still capture
 * the departing player's signals.
 *
 * <p>The backing map is concurrent; the event handlers and the capture path
 * access it from different threads without external synchronisation. Session
 * field publication is governed by the {@code volatile} fields of {@link Session}.
 */
public final class SessionRegistry implements Listener {

    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>(256);

    /**
     * Records or refreshes the session for a connecting player. Runs at the
     * lowest priority so that the address is available to any higher-priority
     * handler on the same event.
     *
     * @param event the pre-login event
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        final UUID uuid = event.getUniqueId();
        sessions.compute(uuid, (key, existing) -> {
            final long now = System.currentTimeMillis();
            if (existing == null) {
                final Session session = new Session(event.getName(), event.getAddress());
                session.touch(now);
                return session;
            }
            existing.address(event.getAddress());
            existing.touch(now);
            return existing;
        });
    }

    /**
     * Enriches the session with the settings available once the player has
     * joined. The client brand may still be null immediately after join; it is
     * captured on a best-effort basis.
     *
     * @param event the join event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        session.locale(player.locale().toString());
        session.brand(player.getClientBrandName());
        session.protocolVersion(player.getProtocolVersion());
        session.viewDistance(player.getClientViewDistance());
        if (player.getMainHand() != null) {
            session.mainHand(player.getMainHand().name());
        }
        session.touch(System.currentTimeMillis());
    }

    /**
     * Refreshes the session on any change to the client's reported settings,
     * including the settings packet the client emits during login.
     *
     * @param event the client-options change event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClientOptions(final PlayerClientOptionsChangeEvent event) {
        final Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) {
            return;
        }
        if (event.getLocale() != null) {
            session.locale(event.getLocale());
        }
        session.viewDistance(event.getViewDistance());
        if (event.getMainHand() != null) {
            session.mainHand(event.getMainHand().name());
        }
        if (event.getSkinParts() != null) {
            session.skinParts(event.getSkinParts().getRaw());
        }
        session.touch(System.currentTimeMillis());
    }

    /**
     * Stamps the session's last-activity time on disconnect so that the
     * maintenance sweep can retire it once the grace period elapses.
     *
     * @param event the quit event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final org.bukkit.event.player.PlayerQuitEvent event) {
        final Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) {
            session.touch(System.currentTimeMillis());
        }
    }

    /**
     * Evicts sessions for players who are offline and whose last activity is
     * older than the grace period. Runs on the main thread, as it consults the
     * online-player set.
     *
     * @param now         current epoch-millisecond timestamp
     * @param graceMillis retention grace period in milliseconds
     * @return the number of sessions evicted
     */
    public int evictStale(final long now, final long graceMillis) {
        int evicted = 0;
        final var iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            final var entry = iterator.next();
            final boolean offline = org.bukkit.Bukkit.getPlayer(entry.getKey()) == null;
            if (offline && now - entry.getValue().lastActivity() > graceMillis) {
                iterator.remove();
                evicted++;
            }
        }
        return evicted;
    }

    /**
     * Resolves the session for a UUID.
     *
     * @param uuid the player UUID
     * @return the session, or null when none is recorded
     */
    public Session get(final UUID uuid) {
        return sessions.get(uuid);
    }

    /**
     * Returns the number of tracked sessions.
     *
     * @return the session count
     */
    public int size() {
        return sessions.size();
    }
}