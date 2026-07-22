package org.synergyst.minutiae.behaviour;

/**
 * Immutable snapshot of the behavioural constraints active on a single player.
 *
 * <p>The record pairs a membership {@code mask} with a parallel {@code expiry}
 * array indexed by {@link Behaviour#ordinal()}. An expiry of {@link Long#MAX_VALUE}
 * denotes a permanent constraint; any other value is an epoch-millisecond
 * deadline. A constraint is active only when both its bit is set and its expiry
 * lies in the future, which is evaluated lazily on read: expired constraints are
 * treated as inactive without rewriting the record, keeping reads on the
 * movement and chat hot paths allocation-free.
 *
 * <p>Records are replaced atomically in {@link BehaviourManager}; instances are
 * never mutated after construction and are safe for concurrent reads.
 */
public final class BehaviourRecord {

    /** Sentinel expiry denoting a permanent constraint. */
    public static final long PERMANENT = Long.MAX_VALUE;

    private final long mask;
    private final long[] expiry;
    private final String reason;

    private BehaviourRecord(final long mask, final long[] expiry, final String reason) {
        this.mask = mask;
        this.expiry = expiry;
        this.reason = reason;
    }

    /**
     * Creates an empty record with no active constraints.
     *
     * @return the empty record
     */
    public static BehaviourRecord empty() {
        return new BehaviourRecord(0L, new long[Behaviour.values().length], null);
    }

    /**
     * Returns a new record with an additional constraint mask applied.
     *
     * <p>For each bit in {@code addMask}, the corresponding expiry is set to the
     * later of its current value and {@code effectiveExpiry}, so overlapping
     * sanctions extend rather than shorten a constraint. The supplied reason
     * becomes the record's reason.
     *
     * @param addMask         constraints to add
     * @param effectiveExpiry expiry to apply to the added constraints, or
     *                        {@link #PERMANENT}
     * @param reason          reason associated with the applied constraints
     * @return a new merged record
     */
    public BehaviourRecord withApplied(final long addMask, final long effectiveExpiry, final String reason) {
        final long[] merged = expiry.clone();
        long bits = addMask;
        while (bits != 0L) {
            final int ordinal = Long.numberOfTrailingZeros(bits);
            bits &= bits - 1;
            merged[ordinal] = Math.max(merged[ordinal], effectiveExpiry);
        }
        return new BehaviourRecord(mask | addMask, merged, reason);
    }

    /**
     * Tests whether a constraint is active at a given time.
     *
     * @param behaviour the constraint
     * @param now       current epoch-millisecond timestamp
     * @return {@code true} when the constraint is present and unexpired
     */
    public boolean has(final Behaviour behaviour, final long now) {
        return behaviour.in(mask) && expiry[behaviour.ordinal()] > now;
    }

    /**
     * Tests whether any constraint remains active at a given time.
     *
     * @param now current epoch-millisecond timestamp
     * @return {@code true} when at least one constraint is present and unexpired
     */
    public boolean any(final long now) {
        long bits = mask;
        while (bits != 0L) {
            final int ordinal = Long.numberOfTrailingZeros(bits);
            bits &= bits - 1;
            if (expiry[ordinal] > now) {
                return true;
            }
        }
        return false;
    }

    /** Returns the raw membership mask, ignoring expiry. */
    public long mask() {
        return mask;
    }

    /** Returns the associated reason, or null when none was recorded. */
    public String reason() {
        return reason;
    }
}