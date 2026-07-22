package org.synergyst.minutiae.net;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Immutable network-layer configuration.
 *
 * <p>Networking is disabled by default: a standalone deployment carries no
 * polling task and no directive traffic. The poll interval is clamped to a
 * floor that keeps the shared backend load negligible while bounding
 * propagation latency; the directive TTL bounds both replay after downtime
 * and table growth.
 *
 * @param enabled        whether the directive bus operates
 * @param configuredId   operator-configured server identifier; blank selects
 *                       the generated persistent identifier
 * @param pollIntervalMs directive poll period in milliseconds
 * @param directiveTtlMs directive lifetime in milliseconds
 */
public record NetworkConfig(boolean enabled,
                            String configuredId,
                            long pollIntervalMs,
                            long directiveTtlMs) {

    /**
     * Materialises configuration from a section, applying fail-safe defaults.
     *
     * @param section the {@code network} section, or null
     * @return an immutable configuration snapshot
     */
    public static NetworkConfig from(final ConfigurationSection section) {
        if (section == null) {
            return new NetworkConfig(false, "", 2_000L, 120_000L);
        }
        final boolean enabled = section.getBoolean("enabled", false);
        final String id = section.getString("server-id", "").trim();
        final long poll = Math.max(250L, section.getLong("poll-interval-ms", 2_000L));
        final long ttl = Math.max(10_000L,
                section.getLong("directive-ttl-seconds", 120L) * 1000L);
        return new NetworkConfig(enabled, id, poll, ttl);
    }
}