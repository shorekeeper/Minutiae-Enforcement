package org.synergyst.minutiae.notify;

/**
 * Delivery mechanism of a notification channel.
 */
public enum ChannelType {

    /** In-game broadcast to online players holding a configured permission. */
    STAFF,

    /** A line written to the server console. */
    CONSOLE,

    /** A line written to the plugin logger. */
    LOG,

    /** A plain-text payload posted to an HTTP webhook endpoint. */
    WEBHOOK;

    /**
     * Parses a channel type by name, case-insensitively.
     *
     * @param raw the type name
     * @return the type, or null when unrecognised
     */
    public static ChannelType parse(final String raw) {
        if (raw == null) {
            return null;
        }
        final String s = raw.trim().toUpperCase();
        for (final ChannelType t : values()) {
            if (t.name().equals(s)) {
                return t;
            }
        }
        return null;
    }
}