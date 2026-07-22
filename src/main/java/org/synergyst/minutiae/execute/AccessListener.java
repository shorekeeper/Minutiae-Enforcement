package org.synergyst.minutiae.execute;

import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.message.MessageKey;
import org.synergyst.minutiae.message.MessageService;
import org.synergyst.minutiae.message.Arg;
import org.synergyst.minutiae.storage.ActiveBan;
import org.synergyst.minutiae.storage.Storage;
import org.synergyst.minutiae.behaviour.Behaviour;

import java.util.concurrent.TimeUnit;

/**
 * Denies connection to players under an active suspension or custody.
 *
 * <p>The check runs on the asynchronous pre-login thread and joins the storage
 * query directly, which is appropriate for an inherently asynchronous event and
 * bounded by the connection pool's acquisition timeout. A query failure is
 * logged and the login permitted, so a transient storage fault never locks out
 * the player base. The refusal screen is rendered in the default locale, since
 * no player-specific locale is available before login completes.
 */
public final class AccessListener implements Listener {

    private final KernelLogger log;
    private final Storage storage;
    private final MessageService messages;
    private static final long ACCESS_TIMEOUT_MS = 3_000L;

    public AccessListener(final KernelLogger log, final Storage storage, final MessageService messages) {
        this.log = log;
        this.storage = storage;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        final long now = System.currentTimeMillis();
        final ActiveBan ban;
        try {
            ban = storage.activeConnectionBan(event.getUniqueId().toString(), now)
                    .get(ACCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (final Exception e) {
            log.error("access", e, "connection ban lookup failed or timed out for %s; permitting",
                    event.getName());
            return;
        }
        if (ban == null) {
            return;
        }
        if (Behaviour.SHADOWED.in(ban.behaviourMask())) {
            // A shadowed suspension or custody admits the player, who is then
            // isolated on join, so the sanction is not revealed at the door.
            return;
        }

        final MessageKey measureKey = MessageKey.measureKey(ban.measure());
        final String reason = ban.reason() != null ? ban.reason()
                : (measureKey != null ? messages.plain((String) null, measureKey) : ban.measure());
        final Component screen = messages.render(null, MessageKey.ACCESS_BANNED,
                Arg.s("reason", reason),
                Arg.measure(ban.measure()));
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, screen);
    }
}