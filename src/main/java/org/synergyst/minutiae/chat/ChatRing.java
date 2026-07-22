package org.synergyst.minutiae.chat;

/**
 * Fixed-capacity ring buffer of a single player's recent chat lines.
 *
 * <p>The buffer is a structure of arrays: a primitive {@code long} array of
 * timestamps parallel to a {@code String} array of line bodies. Recording pushes
 * a line at the head, overwriting the oldest entry when full, so the buffer
 * retains at most its capacity of the most recent lines with no per-record
 * allocation and no boxing. Snapshotting materialises the retained lines in
 * chronological order into freshly allocated parallel arrays.
 *
 * <p>The buffer is not internally synchronised. All access is serialised by the
 * owning service under a per-ring monitor, so a single writer on the chat thread
 * never races a snapshot on the dispatch thread.
 */
final class ChatRing {

    private final long[] stamps;
    private final String[] bodies;
    private int count;
    private int head;
    private long lastActivity;

    ChatRing(final int capacity) {
        this.stamps = new long[capacity];
        this.bodies = new String[capacity];
    }

    /**
     * Records a line at the given time, overwriting the oldest when full.
     *
     * @param now  the line timestamp in epoch milliseconds
     * @param body the sanitised, length-bounded line body
     */
    void record(final long now, final String body) {
        stamps[head] = now;
        bodies[head] = body;
        head = (head + 1) % stamps.length;
        if (count < stamps.length) {
            count++;
        }
        lastActivity = now;
    }

    /**
     * Materialises the retained lines in chronological order.
     *
     * @return a snapshot; empty arrays when no line is retained
     */
    ChatSnapshot snapshot() {
        if (count == 0) {
            return ChatSnapshot.EMPTY;
        }
        final long[] ts = new long[count];
        final String[] msg = new String[count];
        final int start = (head - count + stamps.length * 2) % stamps.length;
        for (int i = 0; i < count; i++) {
            final int idx = (start + i) % stamps.length;
            ts[i] = stamps[idx];
            msg[i] = bodies[idx];
        }
        return new ChatSnapshot(ts, msg);
    }

    /** Returns the last-activity timestamp in epoch milliseconds. */
    long lastActivity() {
        return lastActivity;
    }

    /** Returns the number of retained lines. */
    int size() {
        return count;
    }
}