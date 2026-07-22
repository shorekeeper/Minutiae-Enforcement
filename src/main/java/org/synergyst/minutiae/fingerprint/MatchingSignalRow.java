package org.synergyst.minutiae.fingerprint;

/**
 * A single agreement between an incoming probe and a stored sanctioned account.
 *
 * <p>Each row asserts that the account identified by {@code uuid} bears a signal
 * of type {@code type} whose value equals a probe value, and that this signal was
 * captured at the sanction issue time {@code observedAt}. The scoring engine
 * groups these rows by account to assemble the per-candidate agreement vector.
 *
 * @param uuid       the sanctioned account UUID string
 * @param type       the agreeing signal type code
 * @param value      the agreed signal value
 * @param observedAt the epoch-millisecond capture time of the stored signal
 */
public record MatchingSignalRow(String uuid, int type, String value, long observedAt) {
}