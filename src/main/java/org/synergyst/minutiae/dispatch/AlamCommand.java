package org.synergyst.minutiae.dispatch;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.synergyst.minutiae.command.dsl.Args;
import org.synergyst.minutiae.command.dsl.Ctx;
import org.synergyst.minutiae.command.dsl.Node;
import org.synergyst.minutiae.engine.EventFacts;
import org.synergyst.minutiae.engine.EventKind;
import org.synergyst.minutiae.message.Arg;
import org.synergyst.minutiae.message.MessageKey;
import org.synergyst.minutiae.message.MessageService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Command surface of the dispatch engine: rule enumeration and event
 * simulation.
 *
 * <p>The {@code list} form enumerates armed rules per event kind. The
 * {@code simulate} form constructs synthetic facts from operator input and
 * dispatches them through the engine's simulation path, which evaluates
 * every candidate in forced dry-run without consuming window, sequence,
 * throttle, or mute state. Simulation is the safe rehearsal path for a
 * definition prior to arming its automaton.
 */
public final class AlamCommand {

    private static final String PERMISSION = "minutiae.command.alam";

    private final DispatchEngine engine;
    private final MessageService messages;

    public AlamCommand(final DispatchEngine engine, final MessageService messages) {
        this.engine = engine;
        this.messages = messages;
    }

    /**
     * Builds the command tree.
     *
     * @return the root node
     */
    public Node tree() {
        return Node.literal("alam")
                .permission(PERMISSION)
                .executes(this::list)
                .then(Node.literal("list").executes(this::list))
                .then(Node.literal("simulate")
                        .then(Node.argument("kind", Args.word())
                                .suggests(ctx -> List.of("chat", "break", "login", "evasion"))
                                .then(Node.argument("subject", Args.word())
                                        .suggests(ctx -> onlineNames())
                                        .executes(ctx -> simulate(ctx, ""))
                                        .then(Node.argument("data", Args.greedy())
                                                .executes(ctx -> simulate(ctx, ctx.str("data")))))));
    }

    private void list(final Ctx ctx) {
        messages.send(ctx.sender(), MessageKey.ALAM_LIST_HEADER,
                Arg.n("count", engine.ruleCount()));
        for (final EventKind kind : EventKind.values()) {
            for (final ArmedRule rule : engine.rulesFor(kind)) {
                // A sequence rule is indexed under every step kind; listing by
                // the principal kind avoids duplicate lines.
                if (rule.plan().primaryKind() != kind) {
                    continue;
                }
                messages.send(ctx.sender(), MessageKey.ALAM_LIST_ENTRY,
                        Arg.s("automaton", rule.automaton()),
                        Arg.s("rule", rule.plan().name()),
                        Arg.s("event", kind.name()),
                        Arg.n("stages", rule.plan().eventKinds().length));
            }
        }
    }

    private void simulate(final Ctx ctx, final String data) {
        final EventKind kind = EventKind.parse(ctx.str("kind"));
        if (kind == null) {
            messages.send(ctx.sender(), MessageKey.ALAM_SIM_UNKNOWN_KIND,
                    Arg.s("kind", ctx.str("kind")));
            return;
        }
        final String name = ctx.str("subject");
        engine.simulate(new EventFacts(kind, resolveUuid(name), name,
                factsFor(kind, data)), ctx.sender());
    }

    private Map<String, Object> factsFor(final EventKind kind, final String data) {
        final Map<String, Object> facts = new HashMap<>(4);
        switch (kind) {
            case CHAT -> {
                facts.put("message", data);
                facts.put("length", (long) data.length());
            }
            case BREAK -> facts.put("block",
                    data.isEmpty() ? "STONE" : data.toUpperCase(Locale.ROOT));
            case LOGIN -> facts.put("ip", data.isEmpty() ? "127.0.0.1" : data);
            case EVASION -> {
                double score = 1.0d;
                try {
                    score = Double.parseDouble(data);
                } catch (final NumberFormatException ignored) {
                    // The default score is retained.
                }
                facts.put("score", score);
            }
        }
        return facts;
    }

    private static UUID resolveUuid(final String name) {
        final Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        final OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(name);
        return offline != null ? offline.getUniqueId() : null;
    }

    private static List<String> onlineNames() {
        final List<String> names = new ArrayList<>();
        for (final Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        return names;
    }
}