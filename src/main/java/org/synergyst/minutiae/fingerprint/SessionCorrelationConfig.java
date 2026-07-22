package org.synergyst.minutiae.fingerprint;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Immutable configuration of the session-correlation heuristic.
 *
 * <p>The coefficients weight the three behavioural observations that compose the
 * bounded log-odds adjustment: handoff transitions (positive evidence of a
 * single operator alternating accounts), simultaneous presence (negative
 * evidence, characteristic of distinct co-located people), and active-hour
 * similarity (weak positive evidence). Each observation is capped before
 * weighting, and the total adjustment is clamped, so no behavioural term can
 * dominate the field-agreement evidence.
 *
 * @param handoffGapMillis  maximum gap between one account's logout and another's
 *                          login for the transition to count as a handoff
 * @param handoffBits       bits of positive evidence per handoff transition
 * @param handoffCap        maximum number of handoffs counted
 * @param overlapBits       bits of negative evidence per simultaneous-presence
 *                          interval pair
 * @param overlapCap        maximum number of overlap pairs counted
 * @param hoursBits         maximum bits of positive evidence from active-hour
 *                          similarity
 * @param hoursCenter       similarity threshold below which the active-hour term
 *                          contributes nothing
 * @param totalCapBits      symmetric bound on the total behavioural adjustment
 * @param intervalLimit     maximum session intervals loaded per account
 */
public record SessionCorrelationConfig(long handoffGapMillis,
                                       double handoffBits,
                                       int handoffCap,
                                       double overlapBits,
                                       int overlapCap,
                                       double hoursBits,
                                       double hoursCenter,
                                       double totalCapBits,
                                       int intervalLimit) {

    /** The fail-safe default configuration. */
    public static SessionCorrelationConfig defaults() {
        return new SessionCorrelationConfig(
                120_000L, 0.75d, 8, 1.0d, 6, 0.5d, 0.6d, 6.0d, 200);
    }

    /**
     * Materialises configuration from a section, applying defaults for absent
     * values.
     *
     * @param section the {@code fingerprint.correlation} section, or null
     * @return an immutable configuration snapshot
     */
    public static SessionCorrelationConfig from(final ConfigurationSection section) {
        if (section == null) {
            return defaults();
        }
        final SessionCorrelationConfig d = defaults();
        return new SessionCorrelationConfig(
                section.getLong("handoff-gap-millis", d.handoffGapMillis()),
                section.getDouble("handoff-bits", d.handoffBits()),
                section.getInt("handoff-cap", d.handoffCap()),
                section.getDouble("overlap-bits", d.overlapBits()),
                section.getInt("overlap-cap", d.overlapCap()),
                section.getDouble("hours-bits", d.hoursBits()),
                section.getDouble("hours-center", d.hoursCenter()),
                section.getDouble("total-cap-bits", d.totalCapBits()),
                section.getInt("interval-limit", d.intervalLimit()));
    }
}