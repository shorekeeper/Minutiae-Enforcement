package org.synergyst.minutiae.annotation;

/**
 * Advisory classification of an annotation's parameter for tab completion.
 *
 * <p>The hint drives suggestion rendering only; the authoritative parameter
 * contract remains the {@link ParamValidator} attached to the same
 * {@link AnnotationSpec}. The two are declared side by side in
 * {@link AnnotationCatalog} precisely so that a schema change and its hint are
 * reviewed as one line. A hint that disagrees with its validator produces
 * misleading suggestions but never an incorrect acceptance or rejection.
 *
 * <p>The set is closed and carries no per-constant data; hints that suggest
 * from a dynamic set (channels, rules, recent identifiers) are resolved by the
 * completer against its injected sources.
 */
public enum ParamHint {

    /** No parameters; nothing to suggest inside parentheses. */
    NONE,

    /** One measure name. */
    MEASURE,

    /** One duration expression. */
    DURATION,

    /** One positive integer with no external referent. */
    POSITIVE_INT,

    /** One rule identifier from the rule registry. */
    RULE_ID,

    /** One annotation name from the catalogue. */
    ANNOTATION_NAME,

    /** One or more notification channel names. */
    CHANNELS,

    /** One of the literals {@code on} and {@code off}. */
    TOGGLE,

    /** The evidence schema: empty, {@code required}, or named form. */
    EVIDENCE,

    /** One staff name; suggested from the online-player set. */
    STAFF_NAME,

    /** One sanction identifier; suggested from the recent-identifier ring. */
    SANCTION_ID,

    /** Free text; nothing useful to suggest. */
    TEXT,

    /** An external reference such as a URL; nothing useful to suggest. */
    REFERENCE
}