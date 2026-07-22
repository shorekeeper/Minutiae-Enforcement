package org.synergyst.minutiae.fingerprint;

/**
 * Structure-of-arrays representation of the signals recorded for a sanction,
 * used as the persistence side of the fingerprint system.
 *
 * <p>The three arrays are parallel: element {@code i} of each describes one
 * captured signal, including the scoring weight in effect at capture time. This
 * layout maps directly onto a batched insert into the {@code signals} table.
 *
 * @param types   signal type codes, one per signal
 * @param values  signal values, parallel to {@code types}
 * @param weights capture-time weights, parallel to {@code types}
 */
public record CaptureArrays(int[] types, String[] values, double[] weights) {

    /** Returns the number of signals. */
    public int size() {
        return types.length;
    }
}