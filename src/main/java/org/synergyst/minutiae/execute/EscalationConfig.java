package org.synergyst.minutiae.execute;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Immutable escalation policy configuration.
 *
 * <p>Governs how an active probation window aggravates a repeat offence. In
 * {@code STEP} mode the escalation-ladder index is advanced by one additional
 * rung; otherwise the computed duration is scaled by {@code probationMultiplier}.
 *
 * @param probationStep       whether probation advances the ladder by a step
 *                            rather than scaling the duration
 * @param probationMultiplier duration multiplier applied in multiplier mode
 */
public record EscalationConfig(boolean probationStep, double probationMultiplier) {

    /**
     * Materialises configuration from a section, defaulting to step mode.
     *
     * @param section the {@code escalation} section, or null
     * @return an immutable configuration snapshot
     */
    public static EscalationConfig from(final ConfigurationSection section) {
        if (section == null) {
            return new EscalationConfig(true, 2.0d);
        }
        final String mode = section.getString("probation-mode", "STEP");
        final double mult = section.getDouble("probation-multiplier", 2.0d);
        return new EscalationConfig(mode.equalsIgnoreCase("STEP"), mult);
    }
}