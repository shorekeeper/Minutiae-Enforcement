package org.synergyst.minutiae.storage;

/**
 * A persisted reliability belief for a signal type.
 *
 * <p>The pair {@code (alpha, beta)} are the shape parameters of a Beta
 * distribution over the type's match reliability. Both are strictly positive.
 * The row is the durable form of an evidence model's learned reliability,
 * reloaded at boot and updated on moderator adjudication.
 *
 * @param type      signal type code
 * @param alpha     positive count of match-consistent agreements
 * @param beta      positive count of non-match-consistent agreements
 * @param updatedAt last-update timestamp in epoch milliseconds
 */
public record BeliefRow(int type, double alpha, double beta, long updatedAt) {
}