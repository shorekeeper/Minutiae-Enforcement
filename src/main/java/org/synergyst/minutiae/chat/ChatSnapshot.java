package org.synergyst.minutiae.chat;

/**
 * Immutable, chronological snapshot of a player's retained chat lines.
 *
 * <p>The two arrays are parallel: element {@code i} of each describes one line.
 * This layout maps directly onto a batched persistence insert and onto CSV
 * rendering without intermediate object allocation.
 *
 * @param stamps line timestamps in epoch milliseconds, ascending
 * @param bodies line bodies, parallel to {@code stamps}
 */
public record ChatSnapshot(long[] stamps, String[] bodies) {

    /** The empty snapshot. */
    public static final ChatSnapshot EMPTY = new ChatSnapshot(new long[0], new String[0]);

    /** Returns the number of lines. */
    public int size() {
        return stamps.length;
    }

    /** Reports whether the snapshot holds no lines. */
    public boolean isEmpty() {
        return stamps.length == 0;
    }
}