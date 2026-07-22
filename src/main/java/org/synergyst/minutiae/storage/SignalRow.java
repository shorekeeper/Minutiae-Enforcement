package org.synergyst.minutiae.storage;

/**
 * Read projection of a single persisted fingerprint signal.
 *
 * @param type   signal type code
 * @param value  signal value
 * @param weight capture-time scoring weight
 */
public record SignalRow(int type, String value, double weight) {
}