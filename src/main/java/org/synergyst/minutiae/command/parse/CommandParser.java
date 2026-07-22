package org.synergyst.minutiae.command.parse;

import org.synergyst.minutiae.annotation.AnnotationSyntaxException;
import org.synergyst.minutiae.annotation.AnnotationTokenParser;
import org.synergyst.minutiae.annotation.RawAnnotation;
import org.synergyst.minutiae.message.Arg;
import org.synergyst.minutiae.message.MessageKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Classifies grouped tokens into a {@link ParsedCommand}.
 *
 * <p>Each token is classified by prefix and structure:
 * <ul>
 *   <li>{@code ::key} - a layout selector. At most one is permitted.</li>
 *   <li>{@code @name...} or {@code !@name...} - an annotation token, delegated
 *       to the annotation surface parser.</li>
 *   <li>a token containing a top-level {@code =} - a scalar override, split at
 *       its first unquoted, unparenthesised equals sign.</li>
 *   <li>any other token - the target player. Exactly one is required.</li>
 * </ul>
 *
 * <p>The parser enforces structural cardinality only. It does not resolve the
 * layout, validate annotation semantics, or interpret override keys.
 * Violations raise keyed exceptions rendered in the sender's locale at the
 * command boundary; the malformed-annotation diagnostic embeds the annotation
 * parser's own message as an argument, since that parser is not itself keyed.
 */
public final class CommandParser {

    private CommandParser() {
    }

    /**
     * Parses classified tokens into a command structure.
     *
     * @param tokens the grouped tokens
     * @return the parsed command
     * @throws CommandParseException if cardinality constraints are violated, the
     *                               target is absent, or an annotation token is
     *                               malformed
     */
    public static ParsedCommand parse(final List<String> tokens) {
        String target = null;
        String layoutKey = null;
        final List<RawAnnotation> annotations = new ArrayList<>(8);
        final Map<String, String> overrides = new LinkedHashMap<>(8);

        for (final String token : tokens) {
            if (token.startsWith("::")) {
                if (layoutKey != null) {
                    throw new CommandParseException(MessageKey.PARSE_MULTIPLE_LAYOUTS);
                }
                final String key = token.substring(2);
                if (key.isEmpty()) {
                    throw new CommandParseException(MessageKey.PARSE_EMPTY_LAYOUT);
                }
                layoutKey = key;
            } else if (token.startsWith("@") || token.startsWith("!@")) {
                try {
                    annotations.add(AnnotationTokenParser.parse(token));
                } catch (final AnnotationSyntaxException e) {
                    throw new CommandParseException(MessageKey.PARSE_MALFORMED_ANNOTATION,
                            Arg.s("token", token), Arg.s("error", e.getMessage()));
                }
            } else {
                final int eq = topLevelEquals(token);
                if (eq > 0) {
                    final String key = token.substring(0, eq).trim();
                    final String value = unquote(token.substring(eq + 1).trim());
                    if (key.isEmpty()) {
                        throw new CommandParseException(MessageKey.PARSE_EMPTY_OVERRIDE_KEY,
                                Arg.s("token", token));
                    }
                    if (overrides.putIfAbsent(key, value) != null) {
                        throw new CommandParseException(MessageKey.PARSE_DUPLICATE_OVERRIDE,
                                Arg.s("key", key));
                    }
                } else {
                    if (target != null) {
                        throw new CommandParseException(MessageKey.PARSE_UNEXPECTED_TOKEN,
                                Arg.s("token", token), Arg.s("target", target));
                    }
                    target = token;
                }
            }
        }

        if (target == null) {
            throw new CommandParseException(MessageKey.PARSE_NO_TARGET);
        }
        return new ParsedCommand(target, layoutKey, annotations, overrides);
    }

    private static int topLevelEquals(final String s) {
        boolean quoted = false;
        int depth = 0;
        for (int i = 0, n = s.length(); i < n; i++) {
            final char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
            } else if (c == '=' && !quoted && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String unquote(final String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return s;
    }
}