package org.synergyst.minutiae.annotation;

/**
 * Static description of a known annotation.
 *
 * <p>An annotation is identified by an {@code ordinal} in the range
 * {@code [0, 63]}, permitting its membership in a set to be represented as a
 * single bit within a 64-bit mask. The {@code name} is the invocation identifier
 * without the {@code @} sigil. {@code permission} is the node a sender must hold
 * to apply the annotation. {@code scope} constrains where the annotation may
 * appear. {@code hint} classifies the parameter shape for tab completion and is
 * advisory only. {@code validator} enforces the parameter schema and is
 * authoritative.
 *
 * @param ordinal    dense set index, {@code [0, 63]}
 * @param name       annotation name without sigil
 * @param permission required permission node
 * @param scope      permitted usage context
 * @param hint       advisory parameter classification for completion
 * @param validator  parameter schema validator
 */
public record AnnotationSpec(int ordinal,
                             String name,
                             String permission,
                             Scope scope,
                             ParamHint hint,
                             ParamValidator validator) {

    /**
     * Usage context of an annotation.
     */
    public enum Scope {

        /** Permitted both in layout configuration and inline on a command. */
        CONFIG_AND_INLINE,

        /** Permitted only inline on a command; rejected in layout configuration. */
        INLINE_ONLY
    }

    /** Returns this annotation's membership bit. */
    public long bit() {
        return 1L << ordinal;
    }
}