package org.synergyst.minutiae.command.dsl;

import org.bukkit.command.CommandSender;

/**
 * Context supplied to a {@link Completer}.
 *
 * <p>{@code partial} is the trailing token currently under the cursor, delimited
 * by top-level whitespace with quote and parenthesis spans respected.
 * {@code full} is the entire argument text typed so far. A completer returns
 * candidate replacements for {@code partial}; the framework performs
 * case-insensitive prefix filtering against {@code partial} and offsets each
 * suggestion to the token boundary.
 *
 * @param sender  the requesting sender
 * @param partial the trailing token
 * @param full    the full argument text
 */
public record CompleteCtx(CommandSender sender, String partial, String full) {
}