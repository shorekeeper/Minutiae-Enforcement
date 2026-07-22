package org.synergyst.minutiae.fingerprint;

/**
 * A single signal's contribution to a match score, retained for explanation.
 *
 * <p>The {@code bits} field is the decayed, capped log-likelihood-ratio weight
 * the signal added to the total. A positive value raises the posterior toward a
 * match; a negative value (an agreement on a near-ubiquitous value) lowers it.
 *
 * @param type       signal type code
 * @param value      the agreed value, for display
 * @param bits       decayed, capped evidential weight in bits
 * @param uFrequency the estimated non-match likelihood used for the value
 * @param ageMillis  age of the stored observation at scoring time
 * @param decay      the temporal decay factor applied, in {@code [0, 1]}
 */
public record EvidenceContribution(int type,
                                   String value,
                                   double bits,
                                   double uFrequency,
                                   long ageMillis,
                                   double decay) {
}