package org.synergyst.minutiae.chat;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

/**
 * Immutable configuration governing chat transcript capture.
 *
 * <p>The subsystem records a bounded, per-player ring of recent chat lines and
 * persists a snapshot of that ring when a sanction requests it. Every bound in
 * this record exists to cap memory and to neutralise abuse: {@code perPlayer}
 * bounds the ring depth, {@code maxMessageLength} truncates each stored line,
 * {@code maxTracked} bounds the number of players held in memory at once, and
 * {@code graceMillis} bounds how long a departed player's ring is retained.
 *
 * <p>The capture mode selects when a snapshot is taken. In {@link Mode#ANNOTATION}
 * a snapshot is taken only for a sanction carrying the {@code transcript}
 * annotation. In {@link Mode#ALWAYS} a snapshot is taken for every sanction
 * whose measure blocks connection or applies a behavioural constraint, and for
 * any sanction carrying the annotation.
 *
 * @param enabled          master switch for capture and persistence
 * @param mode             snapshot-selection mode
 * @param perPlayer        ring capacity per player, in lines
 * @param maxMessageLength maximum stored characters per line
 * @param maxTracked       maximum number of players held in memory
 * @param graceMillis      retention grace for a departed player's ring, in millis
 */
public record ChatCaptureConfig(boolean enabled,
                                Mode mode,
                                int perPlayer,
                                int maxMessageLength,
                                int maxTracked,
                                long graceMillis) {

    /** Snapshot-selection mode. */
    public enum Mode {

        /** Snapshot only when the {@code transcript} annotation is present. */
        ANNOTATION,

        /** Snapshot for connection-blocking or behavioural sanctions, or on annotation. */
        ALWAYS;

        /**
         * Parses a mode by name, case-insensitively, defaulting to
         * {@link #ANNOTATION} on an unrecognised value.
         *
         * @param raw the mode name, or null
         * @return the parsed mode
         */
        public static Mode parse(final String raw) {
            if (raw == null) {
                return ANNOTATION;
            }
            return raw.trim().toUpperCase(Locale.ROOT).equals("ALWAYS") ? ALWAYS : ANNOTATION;
        }
    }

    /**
     * Materialises configuration from a section, applying fail-safe defaults.
     *
     * @param section the {@code chat-history} section, or null
     * @return an immutable configuration snapshot
     */
    public static ChatCaptureConfig from(final ConfigurationSection section) {
        if (section == null) {
            return new ChatCaptureConfig(true, Mode.ANNOTATION, 50, 256, 512, 900_000L);
        }
        final boolean enabled = section.getBoolean("enabled", true);
        final Mode mode = Mode.parse(section.getString("mode", "ANNOTATION"));
        final int perPlayer = clamp(section.getInt("per-player", 50), 1, 1024);
        final int maxLen = clamp(section.getInt("max-message-length", 256), 16, 4096);
        final int maxTracked = clamp(section.getInt("max-tracked", 512), 16, 100_000);
        final long grace = Math.max(0L, section.getLong("retention-grace", 900L)) * 1000L;
        return new ChatCaptureConfig(enabled, mode, perPlayer, maxLen, maxTracked, grace);
    }

    private static int clamp(final int value, final int lo, final int hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}