package org.synergyst.minutiae.time;

/**
 * A parsed sanction duration.
 *
 * <p>A duration is either a finite span expressed in milliseconds or the
 * distinguished permanent value. Permanent durations carry a millisecond value
 * of zero and are identified solely by the {@code permanent} flag; callers must
 * never interpret the millisecond field of a permanent duration as a span.
 *
 * <p>Parsing accepts a compound, unit-suffixed grammar: a non-empty sequence of
 * {@code <integer><unit>} segments whose contributions are summed. Recognised
 * units are {@code s} (seconds), {@code m} (minutes), {@code h} (hours),
 * {@code d} (days), and {@code w} (weeks). The literals {@code permanent},
 * {@code perm}, {@code perma}, and {@code forever} denote the permanent value.
 * Parsing is case-insensitive, allocation-light, and rejects malformed input
 * with an {@link IllegalArgumentException} carrying a specific diagnostic.
 *
 * @param millis    finite span in milliseconds; zero when {@code permanent}
 * @param permanent whether this duration denotes a permanent sanction
 */
public record DurationSpec(long millis, boolean permanent) {

    /** The distinguished permanent duration. */
    public static final DurationSpec PERMANENT = new DurationSpec(0L, true);

    private static final long SEC  = 1_000L;
    private static final long MIN  = 60L * SEC;
    private static final long HOUR = 60L * MIN;
    private static final long DAY  = 24L * HOUR;
    private static final long WEEK = 7L * DAY;

    /** The zero finite span, used for instantaneous measures. */
    public static final DurationSpec ZERO = new DurationSpec(0L, false);

    /**
     * Parses a duration expression.
     *
     * @param input the expression; must be non-null and non-blank
     * @return the parsed duration
     * @throws IllegalArgumentException if the expression is empty, contains an
     *                                  unknown unit, has a value without a unit,
     *                                  or a unit without a value
     */
    public static DurationSpec parse(final String input) {
        if (input == null) {
            throw new IllegalArgumentException("duration is null");
        }
        final String s = input.trim().toLowerCase();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("duration is empty");
        }
        if (s.equals("permanent") || s.equals("perm") || s.equals("perma") || s.equals("forever")) {
            return PERMANENT;
        }

        long total = 0L;
        long value = -1L;
        boolean produced = false;

        for (int i = 0, n = s.length(); i < n; i++) {
            final char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                value = (value < 0 ? 0 : value) * 10 + (c - '0');
            } else {
                if (value < 0) {
                    throw new IllegalArgumentException("unit '" + c + "' without preceding value");
                }
                total += value * unitMillis(c);
                value = -1L;
                produced = true;
            }
        }
        if (value >= 0) {
            throw new IllegalArgumentException("trailing value without unit in '" + input + "'");
        }
        if (!produced) {
            throw new IllegalArgumentException("no duration segments in '" + input + "'");
        }
        return new DurationSpec(total, false);
    }

    private static long unitMillis(final char unit) {
        return switch (unit) {
            case 's' -> SEC;
            case 'm' -> MIN;
            case 'h' -> HOUR;
            case 'd' -> DAY;
            case 'w' -> WEEK;
            default -> throw new IllegalArgumentException("unknown duration unit '" + unit + "'");
        };
    }

    /**
     * Renders this duration in the canonical compact form.
     *
     * @return {@code "permanent"} for permanent durations, otherwise the largest
     *         non-zero unit decomposition (e.g. {@code "1d12h"}); a zero finite
     *         span renders as {@code "0s"}
     */
    public String format() {
        if (permanent) {
            return "permanent";
        }
        if (millis == 0L) {
            return "0s";
        }
        long ms = millis;
        final StringBuilder sb = new StringBuilder(12);
        ms = append(sb, ms, WEEK, 'w');
        ms = append(sb, ms, DAY, 'd');
        ms = append(sb, ms, HOUR, 'h');
        ms = append(sb, ms, MIN, 'm');
        append(sb, ms, SEC, 's');
        return sb.toString();
    }

    /**
     * Scales this duration by an integer factor.
     *
     * <p>A permanent duration scales to itself. A finite duration scales by
     * exact multiplication, overflow raising {@link ArithmeticException}.
     *
     * @param factor the non-negative factor
     * @return the scaled duration
     * @throws IllegalArgumentException if the factor is negative
     */
    public DurationSpec times(final long factor) {
        if (permanent) {
            return PERMANENT;
        }
        if (factor < 0) {
            throw new IllegalArgumentException("duration factor must be non-negative");
        }
        return new DurationSpec(Math.multiplyExact(millis, factor), false);
    }

    /**
     * Adds another duration to this one.
     *
     * <p>Adding to or with a permanent duration yields the permanent duration.
     * Two finite durations add by exact addition, overflow raising
     * {@link ArithmeticException}.
     *
     * @param other the duration to add
     * @return the summed duration
     */
    public DurationSpec plus(final DurationSpec other) {
        if (permanent || other.permanent) {
            return PERMANENT;
        }
        return new DurationSpec(Math.addExact(millis, other.millis), false);
    }

    private static long append(final StringBuilder sb, final long ms, final long unit, final char suffix) {
        final long q = ms / unit;
        if (q > 0) {
            sb.append(q).append(suffix);
        }
        return ms % unit;
    }
}