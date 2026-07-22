package org.synergyst.minutiae.command.dsl;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.ApiStatus;

/**
 * Execution context passed to a command handler.
 *
 * <p>Wraps the underlying Brigadier context and exposes a narrow, typed surface:
 * the invoking sender, typed argument accessors keyed by declared argument name,
 * and reply helpers. Handlers never touch Brigadier types directly.
 *
 * <p>Argument accessors delegate to Brigadier's static extractors and therefore
 * throw {@link IllegalArgumentException} if invoked with a name that was not
 * declared on the path leading to the executed node. Optional-style accessors
 * are provided for arguments that may be absent on a given execution path.
 *
 * <p>The context is valid for the synchronous duration of a handler invocation.
 * The captured {@link #sender()} reference remains usable after the handler
 * returns, which permits deferred replies from asynchronous continuations.
 */
@ApiStatus.Experimental
public final class Ctx {

    private final CommandContext<CommandSourceStack> brig;

    Ctx(final CommandContext<CommandSourceStack> brig) {
        this.brig = brig;
    }

    /** Returns the command sender. */
    public CommandSender sender() {
        return brig.getSource().getSender();
    }

    /** Returns the raw Paper command source stack for advanced callers. */
    public CommandSourceStack source() {
        return brig.getSource();
    }

    /**
     * Extracts a string argument.
     *
     * @param name declared argument name
     * @return the argument value
     */
    public String str(final String name) {
        return StringArgumentType.getString(brig, name);
    }

    /**
     * Extracts a string argument that may be absent on the executed path.
     *
     * @param name     declared argument name
     * @param fallback value returned when the argument is not present
     * @return the argument value, or {@code fallback} when absent
     */
    public String optStr(final String name, final String fallback) {
        try {
            return StringArgumentType.getString(brig, name);
        } catch (final IllegalArgumentException absent) {
            return fallback;
        }
    }

    /**
     * Extracts an integer argument.
     *
     * @param name declared argument name
     * @return the argument value
     */
    public int integer(final String name) {
        return IntegerArgumentType.getInteger(brig, name);
    }

    /**
     * Extracts a boolean argument.
     *
     * @param name declared argument name
     * @return the argument value
     */
    public boolean bool(final String name) {
        return BoolArgumentType.getBool(brig, name);
    }

    /**
     * Extracts an argument of arbitrary type. Escape hatch for argument types
     * not covered by the typed accessors above.
     *
     * @param name declared argument name
     * @param type expected runtime type of the parsed value
     * @param <T>  value type
     * @return the argument value
     */
    public <T> T get(final String name, final Class<T> type) {
        return brig.getArgument(name, type);
    }

    /** Sends a pre-built component to the sender. */
    public void reply(final Component component) {
        sender().sendMessage(component);
    }

    /** Sends a coloured plain-text line to the sender. */
    public void reply(final String text, final TextColor colour) {
        sender().sendMessage(Component.text(text, colour));
    }

    /** Sends an uncoloured plain-text line to the sender. */
    public void reply(final String text) {
        sender().sendMessage(Component.text(text));
    }
}