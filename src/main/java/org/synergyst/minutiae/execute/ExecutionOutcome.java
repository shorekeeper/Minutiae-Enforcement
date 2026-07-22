package org.synergyst.minutiae.execute;

import org.synergyst.minutiae.measure.Measure;
import org.synergyst.minutiae.time.DurationSpec;

/**
 * Result of persisting and applying a sanction.
 *
 * <p>The effective measure may differ from the resolved measure when a
 * warn-first gate downgrades an offence to a formal warning. All fields required
 * to apply the sanction's mechanism and report its outcome are captured here, so
 * the completion handler recomputes nothing.
 *
 * @param banId              generated sanction identifier
 * @param effectiveMeasure   measure actually applied, after any warn-first downgrade
 * @param finalDuration      duration after escalation
 * @param expiresAt          expiry timestamp, or zero
 * @param behaviourMask      applied behavioural constraint mask
 * @param priorSanctions     prior non-warning sanction count under the rule
 * @param escalated          whether escalation altered the base duration
 * @param staysActivated     number of previously stayed sanctions activated
 * @param suspendedActivated number of suspended sentences activated by recidivism
 * @param stayed             whether this sanction was recorded in the stayed state
 * @param suspended          whether this sanction was recorded as a suspended sentence
 * @param warned             whether the offence was downgraded to a warning
 * @param warningNumber      ordinal of this warning within its quota, when warned
 * @param warnRequired       warning quota, when warned
 */
public record ExecutionOutcome(long banId,
                               Measure effectiveMeasure,
                               DurationSpec finalDuration,
                               long expiresAt,
                               long behaviourMask,
                               int priorSanctions,
                               boolean escalated,
                               int staysActivated,
                               int suspendedActivated,
                               boolean stayed,
                               boolean suspended,
                               boolean warned,
                               int warningNumber,
                               int warnRequired) {
}