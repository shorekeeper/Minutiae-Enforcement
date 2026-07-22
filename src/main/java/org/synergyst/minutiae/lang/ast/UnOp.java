package org.synergyst.minutiae.lang.ast;

/**
 * Unary operators of the expression grammar. {@link #NOT} is defined over
 * booleans; {@link #NEG} over integers, reals, and finite durations.
 */
public enum UnOp {
    NOT, NEG
}