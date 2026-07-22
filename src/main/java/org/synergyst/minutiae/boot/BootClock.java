package org.synergyst.minutiae.boot;

/**
 * Monotonic origin for boot-relative timing.
 *
 * <p>Captures a single {@link System#nanoTime()} baseline at construction and
 * exposes elapsed measurements relative to it. Immutable and thread-safe.
 */
public final class BootClock {

    private final long baselineNanos;

    public BootClock() {
        this.baselineNanos = System.nanoTime();
    }

    /** Returns the captured monotonic baseline in nanoseconds. */
    public long baselineNanos() {
        return baselineNanos;
    }

    /** Returns nanoseconds elapsed since the baseline. */
    public long elapsedNanos() {
        return System.nanoTime() - baselineNanos;
    }
}