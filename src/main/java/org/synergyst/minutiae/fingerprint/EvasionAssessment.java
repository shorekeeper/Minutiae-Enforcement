package org.synergyst.minutiae.fingerprint;

/**
 * The best-scoring candidate for an incoming probe under the evidence model.
 *
 * <p>{@code uuid} identifies the sanctioned account whose stored signals best
 * explain the probe; {@code evidence} carries the calibrated posterior and its
 * full per-signal decomposition. A null assessment denotes that no stored account
 * shares any signal with the probe.
 *
 * @param uuid     the best-matching sanctioned account UUID string
 * @param evidence the calibrated match evidence
 */
public record EvasionAssessment(String uuid, MatchEvidence evidence) {
}