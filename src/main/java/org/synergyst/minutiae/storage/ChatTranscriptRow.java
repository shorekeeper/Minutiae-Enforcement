package org.synergyst.minutiae.storage;

/**
 * Read projection of a single persisted chat transcript line.
 *
 * @param seq  zero-based ordinal within the transcript, ascending by time
 * @param ts   line timestamp in epoch milliseconds
 * @param body sanitised line body
 */
public record ChatTranscriptRow(int seq, long ts, String body) {
}