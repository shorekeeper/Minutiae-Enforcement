package org.synergyst.minutiae.fingerprint;

/**
 * Structure-of-arrays representation of an incoming connection's signals, used
 * as the query side of evasion scoring.
 *
 * <p>The two arrays are parallel: element {@code i} of {@code types} and
 * {@code values} together describe one signal. This layout mirrors the storage
 * query parameters directly, avoiding intermediate object allocation when
 * building the scoring statement.
 *
 * @param types  signal type codes, one per signal
 * @param values signal values, parallel to {@code types}
 */
public record ProbeArrays(int[] types, String[] values) {

    /** Returns the number of signals. */
    public int size() {
        return types.length;
    }
}