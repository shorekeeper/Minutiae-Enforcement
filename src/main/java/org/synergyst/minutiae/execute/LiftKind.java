package org.synergyst.minutiae.execute;

import java.util.Locale;

/**
 * Enumeration of lift kinds: the legal character of a sanction's removal.
 *
 * <p>A lift is not a single operation semantically. The kind records why the
 * sanction was removed and, critically, governs whether the lifted sanction
 * continues to count toward precedent:
 * <ul>
 *   <li>{@link #VACATE} declares the sanction wrongly issued. The record is
 *       excluded from precedent entirely; a subsequent offence under the same
 *       rule escalates as if the vacated sanction had never existed.</li>
 *   <li>{@link #PARDON} declares the sanction rightly issued but remitted as
 *       an act of clemency. The record is retained in precedent; a repeat
 *       offence escalates on top of it.</li>
 *   <li>{@link #TIME_SERVED} terminates the sanction early on the ground that
 *       its purpose has been fulfilled. Semantically an early expiry: the
 *       record is retained in precedent.</li>
 * </ul>
 *
 * <p>Each constant is persisted by its ordinal in the {@code bans.lift_kind}
 * column. The default column value of zero maps to {@link #VACATE}, which
 * preserves the pre-migration behaviour of every historical lift: before this
 * enumeration existed, a lifted sanction was unconditionally excluded from
 * precedent. Enum declaration order is therefore load-bearing and must never
 * be reordered once released, only appended to.
 */
public enum LiftKind {

    /** The sanction was wrongly issued; the record is expunged from precedent. */
    VACATE,

    /** The sanction was rightly issued but remitted; precedent is retained. */
    PARDON,

    /** The sanction is terminated early as served; precedent is retained. */
    TIME_SERVED;

    private static final LiftKind[] BY_ORDINAL = values();

    /** Returns the persisted integer code for this kind. */
    public int code() {
        return ordinal();
    }

    /**
     * Reports whether a sanction lifted under this kind continues to count
     * toward precedent.
     *
     * @return {@code true} for every kind except {@link #VACATE}
     */
    public boolean retainsPrecedent() {
        return this != VACATE;
    }

    /**
     * Resolves a kind by its persisted code.
     *
     * @param code the integer code
     * @return the corresponding kind; out-of-range codes degrade to
     *         {@link #VACATE}, the fail-safe interpretation that matches the
     *         column default and pre-migration rows
     */
    public static LiftKind fromCode(final int code) {
        if (code < 0 || code >= BY_ORDINAL.length) {
            return VACATE;
        }
        return BY_ORDINAL[code];
    }

    /**
     * Parses a kind from a command-surface token, case-insensitively.
     *
     * <p>Accepted tokens: {@code vacate}, {@code pardon}, {@code served},
     * {@code time-served}.
     *
     * @param raw the token, or null
     * @return the kind, or null when unrecognised
     */
    public static LiftKind parse(final String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "vacate" -> VACATE;
            case "pardon" -> PARDON;
            case "served", "time-served" -> TIME_SERVED;
            default -> null;
        };
    }
}