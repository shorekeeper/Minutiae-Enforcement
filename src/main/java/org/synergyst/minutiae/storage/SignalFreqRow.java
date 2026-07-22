package org.synergyst.minutiae.storage;

/**
 * A row of the signal-frequency aggregate: the number of distinct accounts that
 * bear a given signal value.
 *
 * <p>This aggregate is the corpus-wide collision statistic from which the
 * value-conditioned non-match likelihood is estimated. A high {@code accounts}
 * count denotes a common value (a shared proxy address, a ubiquitous locale) and
 * yields low evidential weight; a count of one denotes a value unique to a single
 * account and yields maximal weight.
 *
 * @param type     signal type code
 * @param value    signal value
 * @param accounts number of distinct accounts bearing the value
 */
public record SignalFreqRow(int type, String value, long accounts) {
}