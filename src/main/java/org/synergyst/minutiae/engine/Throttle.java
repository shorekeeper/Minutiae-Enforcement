package org.synergyst.minutiae.engine;

import java.util.HashMap;
import java.util.Map;

/**
 * Sliding-window firing throttle keyed by automaton name.
 *
 * <p>Each automaton is tracked by a fixed-capacity ring of firing timestamps
 * sized to its per-minute limit. An acquire succeeds when fewer than the limit
 * of firings fall within the trailing one-minute window, in which case the
 * current timestamp is recorded; otherwise it fails without recording. The ring
 * layout holds timestamps in a primitive {@code long} array, avoiding per-firing
 * allocation and boxing on the hot path.
 *
 * <p>The throttle is not internally synchronised; the engine confines all access
 * to a single dispatch path.
 */
public final class Throttle {

    private static final long WINDOW_MS = 60_000L;

    private static final class Ring {
        final long[] stamps;
        int count;
        int head;

        Ring(final int capacity) {
            this.stamps = new long[capacity];
        }
    }

    private final int limit;
    private final Map<String, Ring> rings = new HashMap<>(16);

    /**
     * Creates a throttle with a per-automaton per-minute limit.
     *
     * @param limit maximum firings per minute; must be positive
     */
    public Throttle(final int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("throttle limit must be >= 1");
        }
        this.limit = limit;
    }

    /**
     * Attempts to record a firing for an automaton at a given time.
     *
     * @param automaton the automaton name
     * @param now       the firing timestamp in epoch milliseconds
     * @return {@code true} when the firing was admitted, {@code false} when the
     *         window is saturated
     */
    public boolean tryAcquire(final String automaton, final long now) {
        final Ring ring = rings.computeIfAbsent(automaton, k -> new Ring(limit));
        final long cutoff = now - WINDOW_MS;

        int valid = 0;
        for (int i = 0; i < ring.count; i++) {
            final int idx = (ring.head - ring.count + i + ring.stamps.length * 2) % ring.stamps.length;
            if (ring.stamps[idx] > cutoff) {
                valid++;
            }
        }
        if (valid >= limit) {
            return false;
        }
        ring.stamps[ring.head] = now;
        ring.head = (ring.head + 1) % ring.stamps.length;
        if (ring.count < ring.stamps.length) {
            ring.count++;
        }
        return true;
    }

    /**
     * Returns the number of firings recorded for an automaton within the current
     * window.
     *
     * @param automaton the automaton name
     * @param now       the reference time
     * @return the in-window firing count
     */
    public int windowCount(final String automaton, final long now) {
        final Ring ring = rings.get(automaton);
        if (ring == null) {
            return 0;
        }
        final long cutoff = now - WINDOW_MS;
        int valid = 0;
        for (int i = 0; i < ring.count; i++) {
            final int idx = (ring.head - ring.count + i + ring.stamps.length * 2) % ring.stamps.length;
            if (ring.stamps[idx] > cutoff) {
                valid++;
            }
        }
        return valid;
    }
}