package org.synergyst.minutiae.annotation;

import java.util.List;
import java.util.Map;

/**
 * A syntactically parsed annotation token, prior to any semantic validation.
 *
 * <p>This structure captures the surface form only: the negation marker, the
 * name, positional parameters in source order, and named parameters. It carries
 * no knowledge of whether the annotation exists, whether its parameters are
 * valid, or how it interacts with other annotations; those concerns are handled
 * by the annotation model.
 *
 * @param negated    whether the token was prefixed with {@code !}; always false
 *                   for annotations sourced from configuration
 * @param name       the annotation name, without the {@code @} sigil
 * @param positional positional parameters in source order, never null
 * @param named      named parameters, never null
 */
public record RawAnnotation(boolean negated,
                            String name,
                            List<String> positional,
                            Map<String, String> named) {

    /** Reports whether any parameters are present. */
    public boolean hasParams() {
        return !positional.isEmpty() || !named.isEmpty();
    }

    /**
     * Renders the token back to its canonical source form for display.
     *
     * @return a string such as {@code @evidence(required)} or
     *         {@code @decay(time=30d)}
     */
    public String toDisplay() {
        final StringBuilder sb = new StringBuilder(24);
        if (negated) {
            sb.append('!');
        }
        sb.append('@').append(name);
        if (hasParams()) {
            sb.append('(');
            boolean first = true;
            for (final String p : positional) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(p);
                first = false;
            }
            for (final Map.Entry<String, String> e : named.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
            sb.append(')');
        }
        return sb.toString();
    }
}