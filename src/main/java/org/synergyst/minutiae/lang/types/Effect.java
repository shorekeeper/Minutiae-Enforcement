package org.synergyst.minutiae.lang.types;

/**
 * Effect classification of an expression position.
 *
 * <p>The set forms a two-point lattice ordered {@code PURE < QUERY}. An
 * expression elaborated under an allowance {@code A} may invoke a built-in of
 * effect {@code E} only when {@code E <= A}. Function types carry the effect
 * their bodies were granted; assignability of function types requires the
 * source effect to be less than or equal to the target effect.
 */
public enum Effect {

    /** No capability beyond arithmetic and construction of values. */
    PURE,

    /** Read-only access to the enforcement environment. */
    QUERY;

    /**
     * Reports whether this effect is permitted under an allowance.
     *
     * @param allowance the granted allowance
     * @return {@code true} when {@code this <= allowance}
     */
    public boolean le(final Effect allowance) {
        return ordinal() <= allowance.ordinal();
    }
}