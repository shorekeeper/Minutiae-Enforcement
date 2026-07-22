package org.synergyst.minutiae.web.hive;

/**
 * Type of a Hive value, analogous to a registry value type.
 *
 * <p>The type governs how the panel renders a value's data: a timestamp shows an
 * absolute and relative form, a mask decodes to its member names, a measure or
 * rule renders as a labelled chip, and a link renders as a navigable reference.
 * The set is closed.
 */
public enum ValueType {

    STRING,
    INT,
    TIMESTAMP,
    DURATION,
    MEASURE,
    RULE,
    MASK,
    BOOL,
    LINK,
    ENUM,
    CSV,
    /** A bounded numeric score rendered with threshold-derived emphasis. */
    SCORE,
}