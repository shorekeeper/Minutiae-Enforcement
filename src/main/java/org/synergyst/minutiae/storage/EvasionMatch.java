package org.synergyst.minutiae.storage;

/**
 * Result of scoring a login probe against recorded signals.
 *
 * @param bannedUuid     UUID of the sanctioned account whose signals matched
 * @param score          summed weight of the matched signals
 * @param matchedSignals number of distinct signals that matched
 */
public record EvasionMatch(String bannedUuid, double score, int matchedSignals) {
}