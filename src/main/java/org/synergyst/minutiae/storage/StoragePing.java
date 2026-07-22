package org.synergyst.minutiae.storage;

/**
 * Result of a storage liveness probe.
 *
 * @param ok           whether the probe round-trip succeeded
 * @param roundTripNs  elapsed time of the probe in nanoseconds
 * @param schemaVersion applied schema version reported by the backend, or -1
 *                      when the probe failed
 */
public record StoragePing(boolean ok, long roundTripNs, int schemaVersion) {
}