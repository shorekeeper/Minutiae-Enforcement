package org.synergyst.minutiae.command.dsl;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.ApiStatus;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A node in a declarative command tree.
 *
 * <p>A node is either a literal (a fixed keyword) or an argument (a named,
 * typed value). Nodes are assembled with the fluent {@link #then(Node)},
 * {@link #permission(String)}, and {@link #executes(Handler)} builders and
 * compiled into a Brigadier tree by the framework at registration time.
 *
 * <p>Builder methods return the receiver typed as {@code Node}. Since literal
 * and argument nodes expose no type-specific builders, this loss of static
 * subtype does not impede assembly.
 *
 * <p>Compilation applies three concerns to each node: an optional permission
 * predicate (translated to a Brigadier {@code requires} guard), an optional
 * handler (wrapped so that it returns a uniform success code and its exceptions
 * are reported to the sender), and its children (compiled recursively).
 */
@ApiStatus.Experimental
public sealed abstract class Node permits Node.Literal, Node.Argument {

    private String permission;
    private Handler handler;
    private final List<Node> children = new ArrayList<>(4);

    /**
     * Localised reporter for handler failures, installed once during boot
     * after the message service is online. Before installation, failures fall
     * back to a plain English line, which can only occur if a command executes
     * during the boot sequence itself.
     */
    private static volatile java.util.function.BiConsumer<Ctx, String> failureReporter;

    Node() {
    }

    /**
     * Creates a literal node.
     *
     * @param keyword the fixed keyword
     * @return a new literal node
     */
    public static Node literal(final String keyword) {
        return new Literal(keyword);
    }

    /**
     * Creates an argument node.
     *
     * @param name the declared argument name, used for retrieval via {@link Ctx}
     * @param type the argument type, typically obtained from {@link Args}
     * @return a new argument node
     */
    public static Node argument(final String name, final ArgumentType<?> type) {
        return new Argument(name, type);
    }

    /**
     * Attaches a permission requirement to this node. The node and its subtree
     * are visible and executable only to senders holding the permission.
     *
     * @param permission the permission node
     * @return this node
     */
    public final Node permission(final String permission) {
        this.permission = permission;
        return this;
    }

    private Completer completer;

    /**
     * Attaches a completion provider to this node. Only meaningful on argument
     * nodes; ignored on literal nodes.
     *
     * @param completer the completer
     * @return this node
     */
    public final Node suggests(final Completer completer) {
        this.completer = completer;
        return this;
    }

    /**
     * Attaches an execution handler to this node.
     *
     * @param handler the handler
     * @return this node
     */
    public final Node executes(final Handler handler) {
        this.handler = handler;
        return this;
    }

    /**
     * Adds a child node.
     *
     * @param child the child
     * @return this node
     */
    public final Node then(final Node child) {
        this.children.add(child);
        return this;
    }

    /**
     * Compiles this node and its subtree into a Brigadier command node.
     *
     * @return the compiled node
     */
    final CommandNode<CommandSourceStack> compile() {
        final ArgumentBuilder<CommandSourceStack, ?> builder = newBuilder();

        if (permission != null) {
            final String required = permission;
            final Predicate<CommandSourceStack> guard =
                    source -> source.getSender().hasPermission(required);
            builder.requires(guard);
        }

        if (handler != null) {
            final Handler bound = handler;
            builder.executes(brig -> invoke(bound, brig));
        }

        if (completer != null && this instanceof Argument) {
            @SuppressWarnings("unchecked")
            final RequiredArgumentBuilder<CommandSourceStack, ?> arg =
                    (RequiredArgumentBuilder<CommandSourceStack, ?>) builder;
            arg.suggests(this::suggest);
        }

        for (final Node child : children) {
            builder.then(child.compile());
        }

        return builder.build();
    }

    abstract ArgumentBuilder<CommandSourceStack, ?> newBuilder();

    /**
     * Installs the handler-failure reporter used by every compiled node.
     *
     * @param reporter the reporter, receiving the context and a concise error
     */
    public static void failureReporter(final java.util.function.BiConsumer<Ctx, String> reporter) {
        failureReporter = reporter;
    }

    private static int invoke(final Handler handler, final CommandContext<CommandSourceStack> brig) {
        final Ctx ctx = new Ctx(brig);
        try {
            handler.handle(ctx);
        } catch (final Exception e) {
            final String message = e.getMessage() != null
                    ? e.getMessage() : e.getClass().getSimpleName();
            final java.util.function.BiConsumer<Ctx, String> reporter = failureReporter;
            if (reporter != null) {
                reporter.accept(ctx, message);
            } else {
                ctx.reply("command error: " + message, NamedTextColor.RED);
            }
        }
        return Command.SINGLE_SUCCESS;
    }
    /** Literal (fixed-keyword) node. */
    static final class Literal extends Node {
        private final String keyword;

        Literal(final String keyword) {
            this.keyword = keyword;
        }

        String keyword() {
            return keyword;
        }

        @Override
        ArgumentBuilder<CommandSourceStack, ?> newBuilder() {
            return Commands.literal(keyword);
        }
    }

    private CompletableFuture<Suggestions> suggest(final CommandContext<CommandSourceStack> brig,
                                                   final SuggestionsBuilder sb) {
        final String argText = sb.getInput().substring(sb.getStart());
        final int tokenStart = trailingTokenStart(argText);
        final String partial = argText.substring(tokenStart);

        final SuggestionsBuilder offset = sb.createOffset(sb.getStart() + tokenStart);
        final CompleteCtx ctx = new CompleteCtx(brig.getSource().getSender(), partial, argText);
        for (final String candidate : completer.complete(ctx)) {
            if (candidate.regionMatches(true, 0, partial, 0, partial.length())) {
                offset.suggest(candidate);
            }
        }
        return offset.buildFuture();
    }

    private static int trailingTokenStart(final String s) {
        boolean quoted = false;
        int depth = 0;
        int start = 0;
        for (int i = 0, n = s.length(); i < n; i++) {
            final char c = s.charAt(i);
            if (quoted) {
                if (c == '"' && s.charAt(i - 1) != '\\') {
                    quoted = false;
                }
                continue;
            }
            if (c == '"') {
                quoted = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && depth > 0) {
                depth--;
            } else if (Character.isWhitespace(c) && depth == 0) {
                start = i + 1;
            }
        }
        return start;
    }

    /** Named, typed argument node. */
    static final class Argument extends Node {
        private final String name;
        private final ArgumentType<?> type;

        Argument(final String name, final ArgumentType<?> type) {
            this.name = name;
            this.type = type;
        }

        @Override
        ArgumentBuilder<CommandSourceStack, ?> newBuilder() {
            return Commands.argument(name, type);
        }
    }
}