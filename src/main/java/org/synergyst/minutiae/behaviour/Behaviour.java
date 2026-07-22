package org.synergyst.minutiae.behaviour;

/**
 * Enumeration of behavioural constraints applicable to an online player.
 *
 * <p>Each constant occupies a single bit, indexed by its ordinal, so that any
 * combination of constraints active on a player is representable as one
 * {@code long}. This mask is persisted per sanction and folded across a
 * player's active sanctions at join time.
 *
 * <p>Constraint semantics:
 * <ul>
 *   <li>{@link #MUTED} suppresses chat and configured commands, and informs the
 *       player when they attempt a suppressed action.</li>
 *   <li>{@link #QUARANTINED} confines the player near a configured anchor.</li>
 *   <li>{@link #GHOSTED} hides the player from others and voids their world
 *       interactions; the player retains a normal view.</li>
 *   <li>{@link #RUBBERBAND} reverts movement exceeding a configured leash.</li>
 *   <li>{@link #SHADOWED} isolates the player covertly: hidden from others and
 *       interaction-voided like a ghost, but with chat silently echoed only to
 *       the player rather than blocked, so the isolation is not self-evident.</li>
 * </ul>
 *
 * <p>Enum declaration order is persisted through the bitmask and must never be
 * reordered once released, only appended to, and never beyond 64 constants.
 */
public enum Behaviour {

    MUTED,
    QUARANTINED,
    GHOSTED,
    RUBBERBAND,
    SHADOWED;

    /**
     * Returns this constraint's membership bit.
     *
     * @return {@code 1L << ordinal()}
     */
    public long bit() {
        return 1L << ordinal();
    }

    /**
     * Tests whether a bit is set in a mask.
     *
     * @param mask the membership mask
     * @return {@code true} when this constraint's bit is present in the mask
     */
    public boolean in(final long mask) {
        return (mask & bit()) != 0L;
    }

    /**
     * Builds a mask from a set of constraints.
     *
     * @param behaviours the constraints to include
     * @return the combined membership mask
     */
    public static long mask(final Behaviour... behaviours) {
        long mask = 0L;
        for (final Behaviour b : behaviours) {
            mask |= b.bit();
        }
        return mask;
    }
}