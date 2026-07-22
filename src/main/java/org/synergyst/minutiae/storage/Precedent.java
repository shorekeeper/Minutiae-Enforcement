package org.synergyst.minutiae.storage;

/**
 * Precedent counts for a player under a specific rule (or joinder chain),
 * excluding sanctions whose decay window has elapsed.
 *
 * <p>{@code priorSanctions} tallies non-warning sanctions and drives escalation;
 * {@code priorWarnings} tallies warnings and drives the warn-first gate;
 * {@code inProbation} reports whether an unexpired probation window applies,
 * aggravating a repeat offence.
 *
 * @param priorSanctions count of prior non-warning sanctions
 * @param priorWarnings  count of prior warnings
 * @param inProbation    whether an active probation window applies
 */
public record Precedent(int priorSanctions, int priorWarnings, boolean inProbation) {

    /** The empty precedent, used when a sanction cites no rule. */
    public static final Precedent NONE = new Precedent(0, 0, false);
}