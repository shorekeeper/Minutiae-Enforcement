package org.synergyst.minutiae.storage;

/**
 * Persisted record of the decision inputs and outputs that produced a sanction.
 *
 * <p>The row captures the escalation and gating computation performed at issue
 * time so that a sanction's duration and downgrade may be explained after the
 * fact without recomputation. A {@code ladderIndex} of {@code -1} denotes that
 * no escalation ladder was consulted. A {@code baseMs} or {@code finalMs} of
 * {@code -1} denotes a permanent magnitude; a value of {@code 0} denotes an
 * instantaneous measure or an absent duration.
 *
 * @param banId          owning sanction identifier
 * @param priorSanctions prior non-warning sanction count consulted
 * @param priorWarnings  prior warning count consulted
 * @param inProbation    whether an active probation window applied
 * @param escalated      whether escalation altered the base duration
 * @param ladderIndex    escalation-ladder rung selected, or {@code -1}
 * @param baseMs         base duration in milliseconds before the tariff floor,
 *                       {@code -1} for permanent, {@code 0} for none
 * @param finalMs        final duration in milliseconds after the tariff floor,
 *                       {@code -1} for permanent, {@code 0} for none
 * @param warnDowngrade  whether the warn-first gate downgraded the offence
 */
public record SanctionTraceRow(long banId,
                               int priorSanctions,
                               int priorWarnings,
                               boolean inProbation,
                               boolean escalated,
                               int ladderIndex,
                               long baseMs,
                               long finalMs,
                               boolean warnDowngrade) {
}