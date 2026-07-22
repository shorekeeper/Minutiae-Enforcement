package org.synergyst.minutiae.behaviour.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.synergyst.minutiae.behaviour.Behaviour;
import org.synergyst.minutiae.behaviour.BehaviourConfig;
import org.synergyst.minutiae.behaviour.BehaviourManager;
import org.synergyst.minutiae.message.MessageKey;
import org.synergyst.minutiae.message.MessageService;

import java.util.Locale;

/**
 * Suppresses configured commands for muted players.
 *
 * <p>The leading command token, stripped of its slash and lowercased, is tested
 * against the configured blocked-command set. A match while muted cancels the
 * command and informs the player. Shadowed players are intentionally not
 * restricted here, since their chat is handled by covert redirection rather than
 * blocking; command output visibility for shadowed players is out of scope.
 *
 * <p>The handler runs on the main thread and performs only in-memory lookups
 * behind the emptiness guard.
 */
public final class CommandListener implements Listener {

    private final BehaviourManager manager;
    private final BehaviourConfig config;
    private final MessageService messages;

    public CommandListener(final BehaviourManager manager,
                           final BehaviourConfig config,
                           final MessageService messages) {
        this.manager = manager;
        this.config = config;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        if (manager.isEmpty() || config.blockedCommands().isEmpty()) {
            return;
        }
        final Player player = event.getPlayer();
        if (!manager.has(player.getUniqueId(), Behaviour.MUTED, System.currentTimeMillis())) {
            return;
        }
        final String root = commandRoot(event.getMessage());
        if (config.blockedCommands().contains(root)) {
            event.setCancelled(true);
            messages.send(player, MessageKey.BEHAVIOUR_MUTED);
        }
    }

    private static String commandRoot(final String message) {
        int start = 0;
        if (start < message.length() && message.charAt(start) == '/') {
            start++;
        }
        int end = start;
        while (end < message.length() && !Character.isWhitespace(message.charAt(end))) {
            end++;
        }
        return message.substring(start, end).toLowerCase(Locale.ROOT);
    }
}