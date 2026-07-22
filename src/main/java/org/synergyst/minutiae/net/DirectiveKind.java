package org.synergyst.minutiae.net;

/**
 * Cross-server directive kinds. The set is closed; an unrecognised kind read
 * from storage is skipped, which preserves forward compatibility across
 * mixed-version deployments during a rolling upgrade.
 */
public enum DirectiveKind {

    /** Remove an online subject with a localised kick screen. */
    KICK,

    /** Rebuild the subject's behavioural state from the authoritative tables. */
    SYNC_BEHAVIOUR;

    /**
     * Parses a kind by name.
     *
     * @param raw the stored kind name
     * @return the kind, or null when unrecognised
     */
    public static DirectiveKind parse(final String raw) {
        for (final DirectiveKind k : values()) {
            if (k.name().equals(raw)) {
                return k;
            }
        }
        return null;
    }
}