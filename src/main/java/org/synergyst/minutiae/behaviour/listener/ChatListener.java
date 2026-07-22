package org.synergyst.minutiae.behaviour.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.synergyst.minutiae.behaviour.Behaviour;
import org.synergyst.minutiae.behaviour.BehaviourManager;
import org.synergyst.minutiae.behaviour.BehaviourRecord;
import org.synergyst.minutiae.message.MessageKey;
import org.synergyst.minutiae.message.MessageService;

/**
 * Enforces the chat consequences of the mute and shadow constraints.
 *
 * <p>A muted player's message is cancelled and the player is informed. A
 * shadowed player's message is not cancelled; instead its viewer set is reduced
 * to the sender alone, so the sender observes their own message while no other
 * recipient does. This preserves the shadow illusion, whereas an informative
 * mute notice would reveal the sanction.
 *
 * <p>The handler runs on the asynchronous chat thread. It reads in-memory
 * behavioural state only and issues no blocking call, so it imposes negligible
 * latency on chat dispatch. The emptiness guard short-circuits entirely when no
 * behavioural state exists server-wide.
 */
public final class ChatListener implements Listener {

    private final BehaviourManager manager;
    private final MessageService messages;

    public ChatListener(final BehaviourManager manager, final MessageService messages) {
        this.manager = manager;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        if (manager.isEmpty()) {
            return;
        }
        final Player player = event.getPlayer();
        final BehaviourRecord record = manager.get(player.getUniqueId());
        if (record == null) {
            return;
        }
        final long now = System.currentTimeMillis();

        if (record.has(Behaviour.MUTED, now)) {
            event.setCancelled(true);
            messages.send(player, MessageKey.BEHAVIOUR_MUTED);
            return;
        }
        if (record.has(Behaviour.SHADOWED, now)) {
            event.viewers().clear();
            event.viewers().add(player);
        }
    }
}