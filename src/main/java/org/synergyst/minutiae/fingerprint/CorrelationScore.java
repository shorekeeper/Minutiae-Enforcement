package org.synergyst.minutiae.fingerprint;

/**
 * The outcome of correlating two accounts' temporal activity.
 *
 * <p>{@code behaviouralBits} is the bounded log-odds adjustment, in bits, to be
 * added to a field-agreement match score; the remaining fields are the raw
 * observations that compose it, retained for explanation.
 *
 * @param behaviouralBits bounded log-odds adjustment in bits
 * @param overlapPairs    number of simultaneous-presence interval pairs
 * @param handoffs        number of handoff transitions on a shared address
 * @param overlapMillis   total simultaneous-presence duration in milliseconds
 * @param hourSimilarity  cosine similarity of the two hour-of-day histograms
 */
public record CorrelationScore(double behaviouralBits,
                               int overlapPairs,
                               int handoffs,
                               long overlapMillis,
                               double hourSimilarity) {

    /** The neutral score: no correlation observed. */
    public static final CorrelationScore NEUTRAL = new CorrelationScore(0.0d, 0, 0, 0L, 0.0d);
}