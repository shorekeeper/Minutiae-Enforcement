package org.synergyst.minutiae.storage;

/**
 * A single edge input to the fingerprint cluster computation: one persisted
 * signal joined to the account that bears it.
 *
 * <p>Rows are streamed in ascending {@code (type, value)} order so that a
 * consumer may union all accounts sharing an identical signal by scanning
 * contiguous runs. The layout is a flat tuple to avoid intermediate object
 * graphs during bulk traversal.
 *
 * @param type  signal type code
 * @param value signal value
 * @param uuid  account UUID bearing the signal
 * @param banId sanction identifier the signal was captured against
 */
public record SignalLinkRow(int type, String value, String uuid, long banId) {
}