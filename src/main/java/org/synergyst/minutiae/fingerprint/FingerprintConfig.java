package org.synergyst.minutiae.fingerprint;

import org.bukkit.configuration.ConfigurationSection;
import org.synergyst.minutiae.log.KernelLogger;

/**
 * Immutable fingerprint subsystem configuration.
 *
 * <p>Weights are held in an array indexed by {@link SignalType#code()} for
 * constant-time lookup during capture, avoiding a map indirection on the hot
 * path. A weight absent from configuration falls back to the type's declared
 * default; a negative configured weight is rejected.
 *
 * @param enabled   master switch for capture and scoring
 * @param threshold aggregate weight at or above which a login is flagged
 * @param weights   scoring weights indexed by signal type code
 */
public record FingerprintConfig(boolean enabled, double threshold, double[] weights) {

    /**
     * Materialises configuration from a section, applying defaults for absent
     * values.
     *
     * @param section the {@code fingerprint} section, or null for all-defaults
     * @param log     diagnostic logger
     * @return an immutable configuration snapshot
     */
    public static FingerprintConfig from(final ConfigurationSection section, final KernelLogger log) {
        final boolean enabled = section == null || section.getBoolean("enabled", true);
        final double threshold = section == null ? 0.7d : section.getDouble("threshold", 0.7d);

        final SignalType[] types = SignalType.values();
        final double[] weights = new double[types.length];
        final ConfigurationSection weightSection =
                section == null ? null : section.getConfigurationSection("weights");

        for (final SignalType type : types) {
            final double def = type.defaultWeight();
            double value = def;
            if (weightSection != null) {
                value = weightSection.getDouble(type.configKey(), def);
            }
            if (value < 0.0d) {
                log.warn("fingerprint", "weight '%s' is negative (%.3f); using default %.3f",
                        type.configKey(), value, def);
                value = def;
            }
            weights[type.code()] = value;
        }
        return new FingerprintConfig(enabled, threshold, weights);
    }
}