package org.synergyst.minutiae.measure;

/**
 * Enumeration of enforcement measures, ordered by severity.
 *
 * <p>The severity ordinal establishes a total order used to classify a
 * {@code @commute} as a mitigation (toward lower severity) or an aggravation
 * (toward higher severity). The temporal kind constrains the relationship
 * between a measure and a duration: instantaneous measures forbid a duration,
 * temporal measures require one, and permanent measures fix it.
 */
public enum Measure {

    WARN(0, Temporal.INSTANTANEOUS),
    CENSURE(1, Temporal.INSTANTANEOUS),
    MUTE(2, Temporal.TEMPORAL),
    KICK(3, Temporal.INSTANTANEOUS),
    QUARANTINE(4, Temporal.TEMPORAL),
    SUSPENSION(5, Temporal.TEMPORAL),
    CUSTODY(6, Temporal.PERMANENT);

    /**
     * Temporal classification of a measure.
     */
    public enum Temporal {

        /** No duration; the measure resolves at the instant of application. */
        INSTANTANEOUS,

        /** A finite duration is mandatory. */
        TEMPORAL,

        /** Duration is fixed to permanent. */
        PERMANENT
    }

    private final int severity;
    private final Temporal temporal;

    Measure(final int severity, final Temporal temporal) {
        this.severity = severity;
        this.temporal = temporal;
    }

    /** Returns the severity ordinal, ascending with harshness. */
    public int severity() {
        return severity;
    }

    /** Returns the temporal classification. */
    public Temporal temporal() {
        return temporal;
    }

    /** Reports whether this measure blocks connection to the server. */
    public boolean blocksConnection() {
        return this == SUSPENSION || this == CUSTODY;
    }

    /**
     * Parses a measure by name, case-insensitively.
     *
     * @param raw the measure name
     * @return the measure
     * @throws IllegalArgumentException if the name is unrecognised
     */
    public static Measure parse(final String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("measure is null");
        }
        final String s = raw.trim().toUpperCase();
        for (final Measure m : values()) {
            if (m.name().equals(s)) {
                return m;
            }
        }
        throw new IllegalArgumentException("unknown measure '" + raw + "'");
    }
}