package org.synergyst.minutiae.engine;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Partial-match tracker for ordered event sequences, keyed by an opaque
 * partition string.
 *
 * <p>Each partition holds a single running partial match: the index of the
 * next expected step and the timestamp at which the current partial began. An
 * incoming event that matches the expected step and falls within the span of
 * the partial advances the match; reaching the final step fires the sequence
 * and resets the partition. When the expected step is not the first and the
 * partial has aged beyond the span, the partial is discarded before matching,
 * so a stale partial never fires. An event that does not advance the expected
 * step but matches the first step restarts the partial, so overlapping
 * sequences re-anchor rather than stall.
 *
 * <p>Step matching is supplied by the caller through {@link StepMatcher},
 * decoupling the tracker from any event or guard representation. A matcher
 * must not throw; a matcher that would fail must report a non-match.
 *
 * <p>Idle partitions are reclaimed by an amortised sweep triggered
 * periodically from within {@link #advance}, requiring no external scheduler.
 * The backing partition map is concurrent; per-partition state is confined to
 * the single dispatch path that advances it.
 */
public final class SequenceTracker {

    /**
     * Predicate deciding whether an incoming event satisfies the step at a
     * given index of the tracked sequence.
     */
    @FunctionalInterface
    public interface StepMatcher {

        /**
         * Tests the step at an index against the incoming event.
         *
         * @param stepIndex zero-based step index
         * @param facts     the incoming event facts
         * @return {@code true} when the step is satisfied
         */
        boolean matches(int stepIndex, EventFacts facts);
    }

    private static final int SWEEP_EVERY = 1024;
    private static final long IDLE_MS = 3_600_000L;

    private static final class Progress {
        int step;
        long start;
    }

    private final Map<String, Progress> partitions = new ConcurrentHashMap<>(64);
    private int opCounter;

    /**
     * Advances the partial match for a partition against an incoming event.
     *
     * @param key       the partition key
     * @param stepCount the number of steps in the sequence, at least two
     * @param withinMs  the completion span in milliseconds
     * @param facts     the incoming event facts
     * @param matcher   the step matcher
     * @param now       the event timestamp in epoch milliseconds
     * @return {@code true} when the event completes the sequence, in which
     *         case the partition is reset
     */
    public boolean advance(final String key, final int stepCount, final long withinMs,
                           final EventFacts facts, final StepMatcher matcher, final long now) {
        maybeSweep(now);
        final Progress p = partitions.computeIfAbsent(key, k -> new Progress());

        if (p.step > 0 && now - p.start > withinMs) {
            p.step = 0;
        }

        if (matcher.matches(p.step, facts)) {
            if (p.step == 0) {
                p.start = now;
            }
            p.step++;
            if (p.step == stepCount) {
                p.step = 0;
                return true;
            }
            return false;
        }

        if (p.step > 0 && matcher.matches(0, facts)) {
            p.start = now;
            p.step = 1;
        }
        return false;
    }

    /**
     * Evicts partitions whose partial match began before the idle horizon.
     *
     * @param now      the reference time
     * @param maxAgeMs the idle horizon in milliseconds
     * @return the number of partitions evicted
     */
    public int evictStale(final long now, final long maxAgeMs) {
        final long horizon = now - maxAgeMs;
        int evicted = 0;
        final Iterator<Map.Entry<String, Progress>> it = partitions.entrySet().iterator();
        while (it.hasNext()) {
            final Progress p = it.next().getValue();
            if (p.step == 0 || p.start < horizon) {
                it.remove();
                evicted++;
            }
        }
        return evicted;
    }

    /** Returns the number of tracked partitions. */
    public int size() {
        return partitions.size();
    }

    /**
     * Returns the index of the next expected step for a partition.
     *
     * @param key the partition key
     * @return the step index, or zero when no partial is recorded
     */
    public int stepOf(final String key) {
        final Progress p = partitions.get(key);
        return p == null ? 0 : p.step;
    }

    private void maybeSweep(final long now) {
        if (++opCounter % SWEEP_EVERY == 0) {
            evictStale(now, IDLE_MS);
        }
    }
}