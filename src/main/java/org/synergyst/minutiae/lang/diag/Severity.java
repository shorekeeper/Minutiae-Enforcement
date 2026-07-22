package org.synergyst.minutiae.lang.diag;

/** Severity of a compiler diagnostic. The set is closed. */
public enum Severity {

    /** The construct is rejected; the enclosing declaration does not load. */
    ERROR,

    /** The construct loads; the condition is reported for operator attention. */
    WARNING
}