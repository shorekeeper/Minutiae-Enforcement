package org.synergyst.minutiae.log;

import org.slf4j.Logger;

import java.util.Locale;

/**
 * Timestamped diagnostic emitter modelled after a kernel ring buffer.
 *
 * <p>Each record is prefixed with the elapsed time since a fixed baseline,
 * formatted with microsecond resolution, followed by a subsystem tag. Output
 * is delegated to the underlying SLF4J logger; this class performs formatting
 * only and holds no I/O resources.
 *
 * <p>Instances are immutable after construction and safe for concurrent use.
 * The baseline is captured from {@link System#nanoTime()} and is monotonic;
 * it is not correlated with wall-clock time.
 */
public final class KernelLogger {

    private static final double NANOS_PER_SECOND = 1_000_000_000.0d;

    private final Logger delegate;
    private final long baselineNanos;
    private final boolean verbose;

    /**
     * Constructs a logger bound to the supplied SLF4J delegate.
     *
     * @param delegate      target logger; must not be null
     * @param baselineNanos monotonic origin captured from {@link System#nanoTime()}
     * @param verbose       when false, {@link #trace(String, String, Object...)}
     *                      records are suppressed
     */
    public KernelLogger(final Logger delegate, final long baselineNanos, final boolean verbose) {
        this.delegate = delegate;
        this.baselineNanos = baselineNanos;
        this.verbose = verbose;
    }

    /** Emits an informational record. */
    public void info(final String subsystem, final String fmt, final Object... args) {
        delegate.info(render(subsystem, fmt, args));
    }

    /** Emits a warning record. */
    public void warn(final String subsystem, final String fmt, final Object... args) {
        delegate.warn(render(subsystem, fmt, args));
    }

    /** Emits an error record. */
    public void error(final String subsystem, final String fmt, final Object... args) {
        delegate.error(render(subsystem, fmt, args));
    }

    /** Emits an error record with an attached throwable. */
    public void error(final String subsystem, final Throwable cause, final String fmt, final Object... args) {
        delegate.error(render(subsystem, fmt, args), cause);
    }

    /** Emits a fine-grained record, suppressed unless verbose mode is enabled. */
    public void trace(final String subsystem, final String fmt, final Object... args) {
        if (verbose) {
            delegate.info(render(subsystem, fmt, args));
        }
    }

    private String render(final String subsystem, final String fmt, final Object... args) {
        final double elapsed = (System.nanoTime() - baselineNanos) / NANOS_PER_SECOND;
        final String body = (args == null || args.length == 0)
                ? fmt
                : String.format(Locale.ROOT, fmt, args);
        // Locale.ROOT guarantees a '.' decimal separator irrespective of the
        // host's default locale, keeping diagnostic output byte-stable.
        return String.format(Locale.ROOT, "[%11.6f] %-9s : %s", elapsed, subsystem, body);
    }
}