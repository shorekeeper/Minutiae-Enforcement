package org.synergyst.minutiae.engine;

/**
 * Outcome of gating a rule against an event.
 */
public enum FireDecision {

    /** The rule fires: its guard passed and the throttle admitted the firing. */
    PASS,

    /** The rule's guard was not satisfied. */
    GUARD_FAIL,

    /** The guard evaluation raised an error and was treated as unsatisfied. */
    GUARD_ERROR,

    /** The automaton's per-minute throttle rejected the firing. */
    THROTTLED,

    /** The recurrence window has not yet accrued enough occurrences to fire. */
    WINDOW_WAIT,

    /** The automaton is self-muted after a prior throttle breach. */
    MUTED
}