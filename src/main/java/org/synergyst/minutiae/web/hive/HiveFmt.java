package org.synergyst.minutiae.web.hive;

/**
 * Formatting helpers for Hive value display.
 *
 * <p>Provides a compact relative-time renderer and a duration renderer over
 * millisecond magnitudes, both allocation-light and locale-independent.
 */
public final class HiveFmt {

    private HiveFmt() {
    }

    /**
     * Renders a timestamp relative to a reference time.
     *
     * @param ts  the timestamp in epoch milliseconds
     * @param now the reference time
     * @return a compact relative form such as {@code "3d ago"} or {@code "in 5h"}
     */
    public static String relative(final long ts, final long now) {
        long delta = now - ts;
        final boolean past = delta >= 0;
        delta = Math.abs(delta);
        final String span = durationMs(delta);
        if (span.equals("0s")) {
            return "now";
        }
        return past ? span + " ago" : "in " + span;
    }

    /**
     * Renders a millisecond magnitude in compact largest-unit form.
     *
     * @param ms the magnitude in milliseconds
     * @return a compact form such as {@code "1d12h"}
     */
    public static String durationMs(final long ms) {
        if (ms < 1000) {
            return "0s";
        }
        long remaining = ms;
        final StringBuilder sb = new StringBuilder(12);
        remaining = append(sb, remaining, 604_800_000L, 'w');
        remaining = append(sb, remaining, 86_400_000L, 'd');
        remaining = append(sb, remaining, 3_600_000L, 'h');
        remaining = append(sb, remaining, 60_000L, 'm');
        append(sb, remaining, 1_000L, 's');
        return sb.length() == 0 ? "0s" : sb.toString();
    }

    private static long append(final StringBuilder sb, final long ms, final long unit, final char suffix) {
        final long q = ms / unit;
        if (q > 0 && sb.length() < 6) {
            sb.append(q).append(suffix);
        }
        return ms % unit;
    }
}