package org.synergyst.minutiae.web;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal JSON support with no external dependency.
 *
 * <p>{@link #esc(String)} escapes a string for inclusion in a JSON document.
 * {@link #parseFlat(String)} parses a flat JSON object whose values are strings,
 * numbers, or booleans into a string map, which is sufficient for the panel's
 * small request bodies. Nested structures are not supported by the parser.
 */
public final class Json {

    private Json() {
    }

    /**
     * Escapes a string as a JSON string body, without surrounding quotes.
     *
     * @param s the string, or null
     * @return the escaped body
     */
    public static String esc(final String s) {
        if (s == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0, n = s.length(); i < n; i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Parses a flat JSON object into a string map.
     *
     * @param body the JSON text
     * @return the parsed map; empty when the body is not a flat object
     */
    public static Map<String, String> parseFlat(final String body) {
        final Map<String, String> out = new LinkedHashMap<>();
        if (body == null) {
            return out;
        }
        int i = body.indexOf('{');
        if (i < 0) {
            return out;
        }
        i++;
        final int n = body.length();
        while (i < n) {
            while (i < n && (Character.isWhitespace(body.charAt(i)) || body.charAt(i) == ',')) {
                i++;
            }
            if (i >= n || body.charAt(i) == '}') {
                break;
            }
            if (body.charAt(i) != '"') {
                break;
            }
            final int keyEnd = body.indexOf('"', i + 1);
            if (keyEnd < 0) {
                break;
            }
            final String key = body.substring(i + 1, keyEnd);
            i = body.indexOf(':', keyEnd);
            if (i < 0) {
                break;
            }
            i++;
            while (i < n && Character.isWhitespace(body.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            final String value;
            if (body.charAt(i) == '"') {
                final StringBuilder sb = new StringBuilder();
                i++;
                while (i < n && body.charAt(i) != '"') {
                    char c = body.charAt(i);
                    if (c == '\\' && i + 1 < n) {
                        i++;
                        c = switch (body.charAt(i)) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            case 'r' -> '\r';
                            default -> body.charAt(i);
                        };
                    }
                    sb.append(c);
                    i++;
                }
                i++;
                value = sb.toString();
            } else {
                final int start = i;
                while (i < n && body.charAt(i) != ',' && body.charAt(i) != '}') {
                    i++;
                }
                value = body.substring(start, i).trim();
            }
            out.put(key, value);
        }
        return out;
    }
}