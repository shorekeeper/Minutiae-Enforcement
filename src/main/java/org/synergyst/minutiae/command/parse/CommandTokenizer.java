package org.synergyst.minutiae.command.parse;

import org.synergyst.minutiae.message.Arg;
import org.synergyst.minutiae.message.MessageKey;

import java.util.ArrayList;
import java.util.List;

/**
 * Whitespace tokeniser that respects double-quote and parenthesis grouping.
 *
 * <p>Tokens are delimited by unquoted, top-level whitespace. A double-quoted
 * span suppresses delimiting until its closing quote; a backslash escapes the
 * following character within a quoted span. A parenthesised span, tracked by
 * nesting depth, likewise suppresses delimiting so that annotation parameter
 * lists such as {@code @notify(staff-chat, discord)} survive as a single token.
 *
 * <p>Quotes and parentheses are preserved verbatim in the emitted tokens;
 * unquoting and parameter parsing occur in later stages. The scan is a single
 * left-to-right pass with no backtracking. Violations raise keyed exceptions
 * rendered in the sender's locale at the command boundary.
 */
public final class CommandTokenizer {

    private CommandTokenizer() {
    }

    /**
     * Splits raw input into grouped tokens.
     *
     * @param input the raw command remainder
     * @return the ordered token list; empty when input is blank
     * @throws CommandParseException on an unterminated quote or unbalanced
     *                               parentheses
     */
    public static List<String> tokenize(final String input) {
        final List<String> tokens = new ArrayList<>(8);
        final int n = input.length();

        int i = 0;
        while (i < n && Character.isWhitespace(input.charAt(i))) {
            i++;
        }

        final StringBuilder current = new StringBuilder(32);
        boolean quoted = false;
        int depth = 0;
        boolean active = false;

        for (; i < n; i++) {
            final char c = input.charAt(i);

            if (quoted) {
                current.append(c);
                if (c == '\\' && i + 1 < n) {
                    current.append(input.charAt(++i));
                } else if (c == '"') {
                    quoted = false;
                }
                continue;
            }

            if (c == '"') {
                quoted = true;
                current.append(c);
                active = true;
            } else if (c == '(') {
                depth++;
                current.append(c);
                active = true;
            } else if (c == ')') {
                if (depth == 0) {
                    throw new CommandParseException(MessageKey.PARSE_UNBALANCED_CLOSE,
                            Arg.n("offset", i));
                }
                depth--;
                current.append(c);
            } else if (Character.isWhitespace(c) && depth == 0) {
                if (active) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    active = false;
                }
            } else {
                current.append(c);
                active = true;
            }
        }

        if (quoted) {
            throw new CommandParseException(MessageKey.PARSE_UNTERMINATED_QUOTE);
        }
        if (depth != 0) {
            throw new CommandParseException(MessageKey.PARSE_UNCLOSED_OPEN,
                    Arg.n("count", depth));
        }
        if (active) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}