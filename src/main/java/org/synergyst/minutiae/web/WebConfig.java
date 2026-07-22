package org.synergyst.minutiae.web;

import org.bukkit.configuration.ConfigurationSection;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.security.MessageDigest;
/**
 *
 * Immutable web-panel configuration.
 *
 * <p>The panel is disabled by default and bound to the loopback interface, a
 * fail-safe posture appropriate to a moderation control surface. Access is
 * gated by opaque tokens mapped to a role: {@code read} permits navigation,
 * {@code write} additionally permits actions. A request presents its token in a
 * session cookie.
 *
 * @param enabled whether the panel is served
 * @param bind    bind address
 * @param port    listen port
 * @param tokens  token-to-role map
 */
public record WebConfig(boolean enabled, String bind, int port, Map<String, String> tokens) {

    /**
     * Materialises configuration from a section.
     *
     * @param section the {@code web} section, or null
     * @return an immutable configuration snapshot
     */
    public static WebConfig from(final ConfigurationSection section) {
        if (section == null) {
            return new WebConfig(false, "127.0.0.1", 8787, Map.of());
        }
        final boolean enabled = section.getBoolean("enabled", false);
        final String bind = section.getString("bind", "127.0.0.1");
        final int port = section.getInt("port", 8787);
        final Map<String, String> tokens = new HashMap<>();
        for (final Object raw : section.getList("tokens", java.util.List.of())) {
            if (raw instanceof Map<?, ?> m) {
                final Object token = m.get("token");
                final Object role = m.get("role");
                if (token != null && role != null) {
                    tokens.put(String.valueOf(token), String.valueOf(role));
                }
            }
        }
        return new WebConfig(enabled, bind, port, Map.copyOf(tokens));
    }

    /**
     * Resolves the role of a token.
     *
     * <p>The comparison is performed against every configured token with a
     * length-independent, non-short-circuiting equality test, and scans all
     * entries regardless of an early match, so that neither the comparison time
     * nor the loop duration reveals which token, if any, matched. This denies a
     * timing side channel over the token space.
     *
     * @param token the token, or null
     * @return the role, or null when the token is unknown
     */
    public String roleOf(final String token) {
        if (token == null) {
            return null;
        }
        final byte[] presented = token.getBytes(StandardCharsets.UTF_8);
        String match = null;
        for (final Map.Entry<String, String> e : tokens.entrySet()) {
            if (MessageDigest.isEqual(presented, e.getKey().getBytes(StandardCharsets.UTF_8))) {
                match = e.getValue();
            }
        }
        return match;
    }
}