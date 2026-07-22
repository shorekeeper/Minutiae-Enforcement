package org.synergyst.minutiae.execute;

import org.synergyst.minutiae.time.DurationSpec;

/**
 * A fully resolved, execution-ready lift description.
 *
 * <p>Produced by {@link LiftResolver} from the lift command's token stream and
 * consumed by {@link SanctionLift}. All permission gates that depend only on
 * the request (kind selection, annotation nodes) have been enforced by the
 * resolver; gates that depend on the target sanction (foreign issuer, blocking
 * measure) are enforced by the executor once the sanction is loaded.
 *
 * @param kind           the lift kind
 * @param reason         lift reason assembled from bare tokens, or null
 * @param silent         whether the public lift announcement is suppressed
 * @param dryRun         whether the lift is previewed without effect
 * @param cascade        whether the lift extends over the joinder chain
 * @param note           internal case note, or null
 * @param probationFor   probation window opened by the lift, or null; the
 *                       resolver guarantees this is null for a vacate
 * @param notifyChannels notification channel names, or null when none
 */
public record ResolvedLift(LiftKind kind,
                           String reason,
                           boolean silent,
                           boolean dryRun,
                           boolean cascade,
                           String note,
                           DurationSpec probationFor,
                           String[] notifyChannels) {

    /**
     * Returns the plain lift of a kind: no reason, no modifiers.
     *
     * @param kind the lift kind
     * @return the plain description
     */
    public static ResolvedLift plain(final LiftKind kind) {
        return new ResolvedLift(kind, null, false, false, false, null, null, null);
    }

    /**
     * Returns a copy of this description with a reason.
     *
     * @param newReason the reason, or null
     * @return the copy
     */
    public ResolvedLift withReason(final String newReason) {
        return new ResolvedLift(kind, newReason, silent, dryRun, cascade,
                note, probationFor, notifyChannels);
    }
}