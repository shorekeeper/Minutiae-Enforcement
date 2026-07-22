package org.synergyst.minutiae.dispatch;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.synergyst.minutiae.engine.EventFacts;
import org.synergyst.minutiae.engine.EventKind;

import java.util.HashMap;
import java.util.Map;

/**
 * Translates player-join events into dispatch facts.
 *
 * <p>The handler runs at monitor priority. The connecting address, when
 * available, is exposed as the {@code ip} fact; an unavailable address yields
 * no fact, which the adapter degrades to empty text.
 */
public final class LoginEventListener implements Listener {

    private final DispatchEngine engine;

    public LoginEventListener(final DispatchEngine engine) {
        this.engine = engine;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Map<String, Object> facts = new HashMap<>(2);
        if (event.getPlayer().getAddress() != null
                && event.getPlayer().getAddress().getAddress() != null) {
            facts.put("ip", event.getPlayer().getAddress().getAddress().getHostAddress());
        }
        engine.handle(new EventFacts(EventKind.LOGIN,
                event.getPlayer().getUniqueId(), event.getPlayer().getName(), facts));
    }
}