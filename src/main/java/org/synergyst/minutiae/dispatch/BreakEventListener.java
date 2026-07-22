package org.synergyst.minutiae.dispatch;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.synergyst.minutiae.engine.EventFacts;
import org.synergyst.minutiae.engine.EventKind;

import java.util.Map;

/**
 * Translates block-break events into dispatch facts.
 *
 * <p>The handler runs at monitor priority and never cancels the event. The
 * broken block's material name is exposed as the {@code block} fact.
 */
public final class BreakEventListener implements Listener {

    private final DispatchEngine engine;

    public BreakEventListener(final DispatchEngine engine) {
        this.engine = engine;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        engine.handle(new EventFacts(EventKind.BREAK,
                event.getPlayer().getUniqueId(), event.getPlayer().getName(),
                Map.of("block", event.getBlock().getType().name())));
    }
}