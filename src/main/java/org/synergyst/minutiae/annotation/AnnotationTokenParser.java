package org.synergyst.minutiae.annotation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for the annotation surface grammar.
 *
 * <p>Grammar (informal):
 * <pre>
 *   token       = [ "!" ] , "@" , name , [ "(" , [ params ] , ")" ] ;
 *   name        = name-char , { name-char } ;
 *   name-char   = letter | digit | "-" | "_" ;
 *   params      = item , { "," , item } ;
 *   item        = named | positional ;
 *   named       = identifier , "=" , value ;
 *   positional  = value ;
 *   value       = quoted | bare ;
 * </pre>
 *
 * <p>Comma and equals characters inside a double-quoted value are literal. An
 * item is classified as named only when the text preceding its first top-level
 * {@code =} forms a valid identifier ({@code [A-Za-z_][A-Za-z0-9_-]*}); this
 * prevents values containing {@code =} (such as query strings) from being
 * misread as named parameters. Surrounding double quotes are stripped and the
 * escape sequences {@code \"} and {@code \\} are unescaped.
 *
 * <p>The parser is stateless and allocation-conscious; it performs a single
 * left-to-right scan with no backtracking and no regular expressions.
 */
public final class AnnotationTokenParser {

    private AnnotationTokenParser() {
    }

    /**
     * Parses a single annotation token.
     *
     * @param raw the token text, with optional surrounding whitespace
     * @return the parsed annotation
     * @throws AnnotationSyntaxException if the token is malformed
     */
    public static RawAnnotation parse(final String raw) {
        if (raw == null) {
            throw new AnnotationSyntaxException("null annotation token");
        }
        final String s = raw.trim();
        if (s.isEmpty()) {
            throw new AnnotationSyntaxException("empty annotation token");
        }

        int i = 0;
        boolean negated = false;
        if (s.charAt(i) == '!') {
            negated = true;
            i++;
        }
        if (i >= s.length() || s.charAt(i) != '@') {
            throw new AnnotationSyntaxException("annotation must begin with '@' at offset " + i);
        }
        i++;

        final int nameStart = i;
        while (i < s.length() && isNameChar(s.charAt(i))) {
            i++;
        }
        if (i == nameStart) {
            throw new AnnotationSyntaxException("empty annotation name at offset " + nameStart);
        }
        final String name = s.substring(nameStart, i);

        if (i == s.length()) {
            return new RawAnnotation(negated, name, List.of(), Map.of());
        }
        if (s.charAt(i) != '(') {
            throw new AnnotationSyntaxException("expected '(' or end of token at offset " + i);
        }
        i++;

        final int close = s.lastIndexOf(')');
        if (close < i - 1) {
            throw new AnnotationSyntaxException("unterminated parameter list");
        }
        for (int j = close + 1; j < s.length(); j++) {
            if (!Character.isWhitespace(s.charAt(j))) {
                throw new AnnotationSyntaxException("trailing characters after ')' at offset " + j);
            }
        }

        final String inner = s.substring(i, close).trim();
        if (inner.isEmpty()) {
            return new RawAnnotation(negated, name, List.of(), Map.of());
        }

        final List<String> positional = new ArrayList<>(4);
        final Map<String, String> named = new LinkedHashMap<>(8);

        for (final String item : splitTopLevel(inner)) {
            final String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                throw new AnnotationSyntaxException("empty parameter in '" + inner + "'");
            }
            final int eq = topLevelEquals(trimmed);
            if (eq > 0 && isIdentifier(trimmed, 0, eq)) {
                final String key = trimmed.substring(0, eq).trim();
                final String value = unquote(trimmed.substring(eq + 1).trim());
                if (named.putIfAbsent(key, value) != null) {
                    throw new AnnotationSyntaxException("duplicate named parameter '" + key + "'");
                }
            } else {
                positional.add(unquote(trimmed));
            }
        }
        return new RawAnnotation(negated, name, positional, named);
    }

    private static List<String> splitTopLevel(final String s) {
        final List<String> parts = new ArrayList<>(4);
        boolean quoted = false;
        int start = 0;
        for (int i = 0, n = s.length(); i < n; i++) {
            final char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    private static int topLevelEquals(final String s) {
        boolean quoted = false;
        for (int i = 0, n = s.length(); i < n; i++) {
            final char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                quoted = !quoted;
            } else if (c == '=' && !quoted) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isIdentifier(final String s, final int from, final int to) {
        if (to <= from) {
            return false;
        }
        final char first = s.charAt(from);
        if (!(Character.isLetter(first) || first == '_')) {
            return false;
        }
        for (int i = from + 1; i < to; i++) {
            final char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    private static String unquote(final String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            final String body = s.substring(1, s.length() - 1);
            return body.replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return s;
    }

    private static boolean isNameChar(final char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_';
    }
}