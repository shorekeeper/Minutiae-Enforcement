package org.synergyst.minutiae.lang.ast;

/**
 * One-based source position of a syntactic construct.
 *
 * <p>Positions address the first character of the construct and are carried on
 * every node for diagnostic attribution. Instances are immutable value objects.
 *
 * @param line   one-based line
 * @param column one-based column
 */
public record Pos(int line, int column) {

    /** The distinguished position of synthesised nodes. */
    public static final Pos NONE = new Pos(0, 0);
}