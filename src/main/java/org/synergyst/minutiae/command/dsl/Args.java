package org.synergyst.minutiae.command.dsl;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

/**
 * Factory methods for supported argument types.
 *
 * <p>Each method returns a Brigadier {@link ArgumentType} to be paired with a
 * declared argument name via {@link Node#argument(String, ArgumentType)}. The
 * corresponding value is retrieved in a handler through the matching typed
 * accessor on {@link Ctx}.
 */
public final class Args {

    private Args() {
    }

    /** A single whitespace-delimited token. */
    public static ArgumentType<String> word() {
        return StringArgumentType.word();
    }

    /** A double-quotable string permitting embedded spaces. */
    public static ArgumentType<String> quotable() {
        return StringArgumentType.string();
    }

    /** All remaining input as a single raw string. Must be the terminal argument. */
    public static ArgumentType<String> greedy() {
        return StringArgumentType.greedyString();
    }

    /**
     * A bounded integer.
     *
     * @param min inclusive lower bound
     * @param max inclusive upper bound
     * @return the argument type
     */
    public static ArgumentType<Integer> integer(final int min, final int max) {
        return IntegerArgumentType.integer(min, max);
    }

    /** A boolean literal ({@code true} or {@code false}). */
    public static ArgumentType<Boolean> bool() {
        return BoolArgumentType.bool();
    }
}