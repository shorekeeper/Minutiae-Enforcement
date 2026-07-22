package org.synergyst.minutiae.annotation;

import org.synergyst.minutiae.time.DurationSpec;

import java.util.Set;

/**
 * Reusable {@link ParamValidator} factories covering the parameter shapes used
 * by the annotation catalogue.
 *
 * <p>Each factory returns a stateless validator. Diagnostics name the offending
 * constraint precisely so that configuration and command errors are actionable.
 */
public final class ParamSchemas {

    private ParamSchemas() {
    }

    /** Accepts a token with no parameters of any kind. */
    public static ParamValidator flag() {
        return a -> a.hasParams() ? "takes no parameters" : null;
    }

    /** Accepts exactly one positional parameter and no named parameters. */
    public static ParamValidator singlePositional(final String label) {
        return a -> {
            if (!a.named().isEmpty()) {
                return "does not accept named parameters";
            }
            if (a.positional().size() != 1) {
                return "requires exactly one " + label;
            }
            return null;
        };
    }

    /** Accepts one or more positional parameters and no named parameters. */
    public static ParamValidator oneOrMorePositional(final String label) {
        return a -> {
            if (!a.named().isEmpty()) {
                return "does not accept named parameters";
            }
            if (a.positional().isEmpty()) {
                return "requires at least one " + label;
            }
            return null;
        };
    }

    /** Accepts exactly one positional parameter parseable as a duration. */
    public static ParamValidator duration() {
        return a -> {
            final String single = ParamSchemas.singlePositional("duration").validate(a);
            if (single != null) {
                return single;
            }
            try {
                DurationSpec.parse(a.positional().get(0));
                return null;
            } catch (final IllegalArgumentException e) {
                return "invalid duration: " + e.getMessage();
            }
        };
    }

    /** Accepts exactly one positional parameter parseable as a positive integer. */
    public static ParamValidator positiveInt() {
        return a -> {
            final String single = ParamSchemas.singlePositional("integer").validate(a);
            if (single != null) {
                return single;
            }
            final int value;
            try {
                value = Integer.parseInt(a.positional().get(0));
            } catch (final NumberFormatException e) {
                return "not an integer: '" + a.positional().get(0) + "'";
            }
            return value > 0 ? null : "must be a positive integer";
        };
    }

    /** Accepts exactly one positional parameter drawn from a fixed set. */
    public static ParamValidator oneOf(final String... allowed) {
        final Set<String> set = Set.of(allowed);
        return a -> {
            final String single = ParamSchemas.singlePositional("value").validate(a);
            if (single != null) {
                return single;
            }
            final String v = a.positional().get(0);
            return set.contains(v) ? null : "must be one of " + set;
        };
    }

    /**
     * Accepts the evidence schema: no parameters (implicitly required), a single
     * positional {@code required}, or named parameters constrained to
     * {@code url} and {@code type} with {@code url} mandatory. Positional and
     * named forms are mutually exclusive.
     */
    public static ParamValidator evidence() {
        return a -> {
            if (a.named().isEmpty() && a.positional().isEmpty()) {
                return null;
            }
            if (!a.named().isEmpty()) {
                if (!a.positional().isEmpty()) {
                    return "positional and named forms are mutually exclusive";
                }
                for (final String key : a.named().keySet()) {
                    if (!key.equals("url") && !key.equals("type")) {
                        return "unknown named parameter '" + key + "' (allowed: url, type)";
                    }
                }
                return a.named().containsKey("url") ? null : "named form requires 'url'";
            }
            if (a.positional().size() == 1 && a.positional().get(0).equals("required")) {
                return null;
            }
            return "expected no parameters, 'required', or url=/type=";
        };
    }
}