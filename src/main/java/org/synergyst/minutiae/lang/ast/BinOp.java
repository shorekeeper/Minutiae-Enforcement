package org.synergyst.minutiae.lang.ast;

/**
 * Binary operators of the expression grammar.
 *
 * <p>Operand typing is enforced by elaboration and admits no implicit
 * coercion: arithmetic is defined over integers, reals, and durations
 * (duration-by-integer scaling and duration addition only); relational
 * comparison is defined within one numeric or duration type; equality is
 * defined within one type; {@link #MATCHES} is defined over text against a
 * text pattern; conjunction and disjunction are defined over booleans and
 * evaluate with short circuit.
 */
public enum BinOp {
    OR, AND,
    EQ, NE,
    LT, GT, LE, GE, MATCHES,
    ADD, SUB, MUL, DIV
}