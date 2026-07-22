package org.synergyst.minutiae.engine;

import java.util.Locale;

/**
 * Atomic platform event a rule may bind to.
 *
 * <p>The set is closed and maps directly onto the raw event name that follows
 * the {@code on} keyword in a rule declaration. Recognition is
 * case-insensitive.
 */
public enum EventKind {

    /** A chat message. Subject is the speaker. Field {@code message}. */
    CHAT,

    /** A block break. Subject is the breaker. Field {@code block}. */
    BREAK,

    /** A player connection. Subject is the joining player. Field {@code ip}. */
    LOGIN,

    /** An evasion flag. Subject is the flagged account. Fields {@code score}, {@code banned}. */
    EVASION;

    /**
     * Resolves an event kind from a raw name.
     *
     * @param raw the event name as written after {@code on}
     * @return the kind, or null when unrecognised
     */
    public static EventKind parse(final String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "chat" -> CHAT;
            case "break" -> BREAK;
            case "login" -> LOGIN;
            case "evasion" -> EVASION;
            default -> null;
        };
    }
}