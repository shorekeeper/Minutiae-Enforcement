package org.synergyst.minutiae.engine;

import java.util.UUID;

/**
 * Provider of the built-in getters a guard expression may invoke.
 *
 * <p>The environment abstracts every source outside the event facts: precedent
 * counts, fingerprint scores, and online status. Abstracting these behind an
 * interface keeps the guard evaluator pure and permits deterministic testing and
 * simulation with a synthetic environment.
 */
public interface GuardEnvironment {

    /**
     * Returns the prior non-warning sanction count for a subject under a rule.
     *
     * @param subject subject account, possibly null
     * @param rule    rule identifier
     * @return the precedent count, or zero when unavailable
     */
    long precedent(UUID subject, String rule);

    /**
     * Returns the current fingerprint evasion score for a subject.
     *
     * @param subject subject account, possibly null
     * @return the score in the range {@code [0, 1]}, or zero when unavailable
     */
    double fingerprintScore(UUID subject);

    /**
     * Reports whether a subject is currently online.
     *
     * @param subject subject account, possibly null
     * @return {@code true} when online
     */
    boolean isOnline(UUID subject);

    /**
     * Returns the current epoch-millisecond timestamp.
     *
     * @return the current time
     */
    long now();
}