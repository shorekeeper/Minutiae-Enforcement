package org.synergyst.minutiae.fingerprint;

/**
 * Provider of the collision statistics required to estimate the non-match
 * likelihood of a signal agreement.
 *
 * <p>The evidence model requires, for each candidate agreement on a signal of
 * type {@code t} and value {@code v}, the number of distinct accounts that bear
 * that value, together with the total number of distinct accounts in the corpus.
 * These two quantities yield the inverse-document-frequency estimate of the
 * probability that two independently drawn distinct accounts agree on the value,
 * which is the non-match likelihood {@code u}.
 *
 * <p>Abstracting these statistics behind an interface keeps the evidence model
 * pure and independent of the persistence layer, and permits deterministic
 * testing with a synthetic corpus. Implementations are expected to answer from a
 * precomputed, periodically refreshed aggregate rather than a per-query scan.
 */
public interface FrequencyOracle {

    /**
     * Returns the number of distinct accounts bearing a signal value.
     *
     * @param type  the signal type code
     * @param value the signal value
     * @return the distinct-account count, at least one when the value is known,
     *         and zero when the value has never been observed
     */
    long accountsBearing(int type, String value);

    /**
     * Returns the total number of distinct accounts in the corpus.
     *
     * @return the corpus size, never less than one
     */
    long totalAccounts();
}