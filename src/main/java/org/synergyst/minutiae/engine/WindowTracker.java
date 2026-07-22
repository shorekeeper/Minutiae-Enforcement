package org.synergyst.minutiae.engine;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window recurrence tracker keyed by an opaque partition string.
 *
 * <p>Each partition is tracked by a fixed-capacity ring of occurrence
 * timestamps sized to its required recurrence count. Recording an occurrence
 * pushes the current timestamp, overwriting the oldest when the ring is full, so
 * the ring always retains the most recent {@code count} timestamps. The window
 * triggers when all {@code count} retained timestamps fall within the trailing
 * span; on triggering, the partition's ring is cleared so that recurrence must
 * re-accrue from zero, bounding the firing rate a sustained event stream can
 * induce.
 *
 * <p>The ring holds timestamps in a primitive {@code long} array, avoiding
 * per-occurrence allocation and boxing on the hot path. Idle partitions are
 * reclaimed by an amortised sweep triggered periodically from within
 * {@link #record}, so a partition for a subject that ceases to offend does not
 * accumulate indefinitely without any external scheduler.
 *
 * <p>The backing partition map is concurrent; per-partition ring state is
 * confined to the single dispatch path that records against it.
 */
public final class WindowTracker {

    private static final int SWEEP_EVERY = 1024;
    private static final long IDLE_MS = 3_600_000L;

    private static final class Ring {
        final long[] stamps;
        int count;
        int head;

        Ring(final int capacity) {
            this.stamps = new long[capacity];
        }

        long newest() {
            if (count == 0) {
                return Long.MIN_VALUE;
            }
            final int idx = (head - 1 + stamps.length) % stamps.length;
            return stamps[idx];
        }

        void clear() {
            count = 0;
            head = 0;
        }
    }

    private final Map<String, Ring> partitions = new ConcurrentHashMap<>(64);
    private int opCounter;

    /**
     * Records an occurrence against a partition and reports whether the window
     * triggers.
     *
     * @param key      the partition key
     * @param now      the occurrence timestamp in epoch milliseconds
     * @param count    the required recurrence count; must be positive
     * @param withinMs the trailing span in milliseconds
     * @return {@code true} when the recurrence threshold is met within the span,
     *         in which case the partition is reset
     */
    public boolean record(final String key, final long now, final int count, final long withinMs) {
        maybeSweep(now);

        final Ring ring = partitions.computeIfAbsent(key, k -> new Ring(count));
        ring.stamps[ring.head] = now;
        ring.head = (ring.head + 1) % ring.stamps.length;
        if (ring.count < ring.stamps.length) {
            ring.count++;
        }

        if (ring.count < count) {
            return false;
        }
        final long cutoff = now - withinMs;
        int valid = 0;
        for (int i = 0; i < ring.count; i++) {
            final int idx = (ring.head - ring.count + i + ring.stamps.length * 2) % ring.stamps.length;
            if (ring.stamps[idx] > cutoff) {
                valid++;
            }
        }
        if (valid >= count) {
            ring.clear();
            return true;
        }
        return false;
    }

    /**
     * Evicts partitions whose most recent occurrence predates the idle horizon.
     *
     * @param now       the reference time in epoch milliseconds
     * @param maxAgeMs  the idle horizon in milliseconds
     * @return the number of partitions evicted
     */
    public int evictStale(final long now, final long maxAgeMs) {
        final long horizon = now - maxAgeMs;
        int evicted = 0;
        final Iterator<Map.Entry<String, Ring>> it = partitions.entrySet().iterator();
        while (it.hasNext()) {
            final Ring ring = it.next().getValue();
            if (ring.count == 0 || ring.newest() < horizon) {
                it.remove();
                evicted++;
            }
        }
        return evicted;
    }

    /**
     * Returns the number of in-window occurrences retained for a partition.
     *
     * @param key the partition key
     * @param now the reference time
     * @param withinMs the trailing span
     * @return the retained in-window occurrence count
     */
    public int windowCount(final String key, final long now, final long withinMs) {
        final Ring ring = partitions.get(key);
        if (ring == null) {
            return 0;
        }
        final long cutoff = now - withinMs;
        int valid = 0;
        for (int i = 0; i < ring.count; i++) {
            final int idx = (ring.head - ring.count + i + ring.stamps.length * 2) % ring.stamps.length;
            if (ring.stamps[idx] > cutoff) {
                valid++;
            }
        }
        return valid;
    }

    /** Returns the number of tracked partitions. */
    public int size() {
        return partitions.size();
    }

    private void maybeSweep(final long now) {
        if (++opCounter % SWEEP_EVERY == 0) {
            evictStale(now, IDLE_MS);
        }
    }
}