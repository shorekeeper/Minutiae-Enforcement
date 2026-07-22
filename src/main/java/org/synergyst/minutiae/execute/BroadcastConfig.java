package org.synergyst.minutiae.execute;

import org.bukkit.configuration.ConfigurationSection;
import org.synergyst.minutiae.measure.Measure;

import java.util.EnumSet;
import java.util.Set;

/**
 * Immutable broadcast configuration.
 *
 * <p>Broadcasting is governed by a master switch and a set of measures eligible
 * for announcement. The measure set is held as an {@link EnumSet} for
 * constant-time membership tests on the enforcement path. Lift announcements are
 * governed separately by {@code announceLift}, gated only by the master switch.
 *
 * @param enabled      master switch
 * @param measures     measures eligible for public announcement
 * @param announceLift whether lifted sanctions are announced publicly
 */
public record BroadcastConfig(boolean enabled, Set<Measure> measures, boolean announceLift) {

    /**
     * Materialises configuration from a section, defaulting to enabled with the
     * connection-blocking and behavioural measures and with lift announcements
     * on when the section is absent.
     *
     * @param section the {@code broadcast} section, or null
     * @return an immutable configuration snapshot
     */
    public static BroadcastConfig from(final ConfigurationSection section) {
        if (section == null) {
            return new BroadcastConfig(true, EnumSet.of(
                    Measure.SUSPENSION, Measure.CUSTODY, Measure.KICK,
                    Measure.MUTE, Measure.QUARANTINE), true);
        }
        final boolean enabled = section.getBoolean("enabled", true);
        final boolean announceLift = section.getBoolean("lift", true);
        final EnumSet<Measure> measures = EnumSet.noneOf(Measure.class);
        for (final String name : section.getStringList("measures")) {
            try {
                measures.add(Measure.parse(name));
            } catch (final IllegalArgumentException ignored) {
                // Unknown measure names are skipped; validation belongs to the
                // layout and command layers.
            }
        }
        return new BroadcastConfig(enabled, measures, announceLift);
    }

    /**
     * Tests whether a measure is eligible for broadcast.
     *
     * @param measure the measure
     * @return {@code true} when broadcasting is enabled and the measure is listed
     */
    public boolean broadcasts(final Measure measure) {
        return enabled && measures.contains(measure);
    }
}