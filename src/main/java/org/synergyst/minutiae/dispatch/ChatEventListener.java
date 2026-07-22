package org.synergyst.minutiae.dispatch;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.synergyst.minutiae.engine.EventFacts;
import org.synergyst.minutiae.engine.EventKind;

import java.util.Map;

/**
 * Translates chat events into dispatch facts.
 *
 * <p>The handler runs at monitor priority so that its observation does not
 * influence chat delivery, and never cancels the event: engine effects are
 * expressed through issued sanctions, not by suppressing the triggering
 * message. The message component is serialised to plain text; the
 * {@code length} fact carries the serialised length.
 */
public final class ChatEventListener implements Listener {

    private final DispatchEngine engine;
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

    public ChatEventListener(final DispatchEngine engine) {
        this.engine = engine;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        final Player player = event.getPlayer();
        final String message = plain.serialize(event.message());
        engine.handle(new EventFacts(EventKind.CHAT,
                player.getUniqueId(), player.getName(),
                Map.of("message", message, "length", (long) message.length())));
    }
}