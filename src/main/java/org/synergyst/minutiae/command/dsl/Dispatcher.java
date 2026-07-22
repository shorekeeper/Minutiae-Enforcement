package org.synergyst.minutiae.command.dsl;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;
import org.synergyst.minutiae.log.KernelLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects declarative command trees and registers them with the Paper command
 * registrar in a single lifecycle handler.
 *
 * <p>Roots are staged via {@link #register(Node, String)} during boot and
 * committed by {@link #arm()}, which installs the registrar handler. Each root
 * must compile to a Brigadier literal node; a non-literal root is a programming
 * error and is rejected at registration time.
 */
@ApiStatus.Experimental
public final class Dispatcher {

    private record Entry(Node root, String description, String literal) {
    }

    private final JavaPlugin plugin;
    private final KernelLogger log;
    private final List<Entry> entries = new ArrayList<>(8);

    public Dispatcher(final JavaPlugin plugin, final KernelLogger log) {
        this.plugin = plugin;
        this.log = log;
    }

    /**
     * Stages a command tree for registration.
     *
     * @param root        the root node, which must be a literal
     * @param description a short description surfaced by the server
     */
    public void register(final Node root, final String description) {
        if (!(root instanceof Node.Literal literal)) {
            throw new IllegalArgumentException("command root must be a literal node");
        }
        entries.add(new Entry(root, description, literal.keyword()));
    }

    /**
     * Installs the registrar lifecycle handler that compiles and registers every
     * staged root. Compilation is deferred to handler invocation, as required by
     * the Paper command lifecycle.
     */
    @SuppressWarnings("UnstableApiUsage")
    public void arm() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final var registrar = event.registrar();
            for (final Entry entry : entries) {
                final CommandNode<CommandSourceStack> compiled = entry.root().compile();
                registrar.register((LiteralCommandNode<CommandSourceStack>) compiled, entry.description());
                log.trace("commands", "registered '/%s'", entry.literal());
            }
        });
        log.info("commands", "dispatcher armed with %d root(s)", entries.size());
    }
}