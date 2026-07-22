package org.synergyst.minutiae.lang.ast;

import java.util.List;

/**
 * A pattern of a {@code match} arm.
 *
 * <p>A pattern is an exact integer, an inclusive integer range, an enum-path
 * constant, or the wildcard. Exhaustiveness over the subject type is enforced
 * by elaboration: a match without a wildcard arm must cover its subject type
 * completely, and a match with unreachable arms is rejected.
 */
public sealed interface Pattern permits Pattern.IntPat, Pattern.RangePat,
        Pattern.PathPat, Pattern.WildPat {

    /** Returns the pattern's source position. */
    Pos pos();

    /** An exact integer pattern. */
    record IntPat(long value, Pos pos) implements Pattern {
    }

    /** An inclusive integer range pattern; {@code lo <= hi} is verified. */
    record RangePat(long lo, long hi, Pos pos) implements Pattern {
    }

    /** An enum-constant pattern addressed by path segments. */
    record PathPat(List<String> segments, Pos pos) implements Pattern {
    }

    /** The catch-all wildcard pattern. */
    record WildPat(Pos pos) implements Pattern {
    }
}