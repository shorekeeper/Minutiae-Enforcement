package org.synergyst.minutiae.fingerprint;

import java.util.List;

/**
 * The outcome of scoring a candidate pair under the evidence model.
 *
 * <p>{@code totalBits} is the posterior log-odds in bits,
 * {@code S = priorBits + field-agreement bits + behavioural bits}. The
 * posterior probability is {@code probability = 1 / (1 + 2^(-S))}. The
 * contributions enumerate, in input order, the per-signal evidential weights
 * that compose the field-agreement portion; {@code correlation} carries the
 * behavioural adjustment and its raw observations, {@link CorrelationScore#NEUTRAL}
 * when no correlation was computed.
 *
 * @param probability   posterior probability of shared identity in {@code [0,1]}
 * @param totalBits     posterior log-odds in bits
 * @param priorBits     prior log-odds in bits
 * @param contributions per-signal evidential contributions, in input order
 * @param correlation   behavioural correlation adjustment, never null
 */
public record MatchEvidence(double probability,
                            double totalBits,
                            double priorBits,
                            List<EvidenceContribution> contributions,
                            CorrelationScore correlation) {

    /** The empty evidence: no contributing signal, probability at the prior. */
    public static MatchEvidence prior(final double priorBits) {
        return new MatchEvidence(1.0 / (1.0 + Math.pow(2.0, -priorBits)),
                priorBits, priorBits, List.of(), CorrelationScore.NEUTRAL);
    }

    /**
     * Returns this evidence adjusted by a behavioural correlation score: the
     * bounded log-odds adjustment is added to the total and the posterior is
     * recomputed. The field-agreement decomposition is retained unchanged.
     *
     * @param score the correlation score
     * @return the adjusted evidence
     */
    public MatchEvidence adjusted(final CorrelationScore score) {
        final double s = totalBits + score.behaviouralBits();
        return new MatchEvidence(1.0 / (1.0 + Math.pow(2.0, -s)),
                s, priorBits, contributions, score);
    }
}