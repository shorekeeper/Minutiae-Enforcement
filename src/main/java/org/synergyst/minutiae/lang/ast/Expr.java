package org.synergyst.minutiae.lang.ast;

import java.util.List;

/**
 * An expression of the definition language.
 *
 * <p>Every expression denotes a value; the language has no expression-level
 * side effects. Effect capability (pure computation versus environment query)
 * is a property of the position in which an expression is elaborated, not of
 * its syntax.
 *
 * <p>Structural notes:
 * <ul>
 *   <li>{@link Member} covers both record field access and named-value
 *       composition; the two are distinguished by elaboration from the
 *       receiver's type.</li>
 *   <li>{@link RecordLit} with a null target is an anonymous record literal
 *       whose type is supplied by the expected type of its position.</li>
 *   <li>{@link Instantiate} arguments are compile-time expressions; their
 *       admissibility against the target's parameter kinds is verified during
 *       elaboration.</li>
 * </ul>
 */
public sealed interface Expr permits Expr.IntLit, Expr.RealLit, Expr.BoolLit,
        Expr.DurLit, Expr.TextLit, Expr.RuleLit, Expr.NameRef, Expr.PathRef,
        Expr.Member, Expr.Call, Expr.Instantiate, Expr.RecordLit, Expr.ListLit,
        Expr.Lambda, Expr.Match, Expr.Binary, Expr.Unary, Expr.With {

    /** Returns the expression's source position. */
    Pos pos();

    /** An integer literal. */
    record IntLit(long value, Pos pos) implements Expr {
    }

    /** A real literal. */
    record RealLit(double value, Pos pos) implements Expr {
    }

    /** A boolean literal. */
    record BoolLit(boolean value, Pos pos) implements Expr {
    }

    /** A duration literal, retained in surface form and decoded once. */
    record DurLit(org.synergyst.minutiae.time.DurationSpec value, Pos pos) implements Expr {
    }

    /** A text literal, escape-decoded. */
    record TextLit(String value, Pos pos) implements Expr {
    }

    /** A rule-identifier literal. */
    record RuleLit(String id, Pos pos) implements Expr {
    }

    /** A bare name reference. */
    record NameRef(String name, Pos pos) implements Expr {
    }

    /** A path reference of two or more {@code ::}-joined segments. */
    record PathRef(List<String> segments, Pos pos) implements Expr {
    }

    /** Field access or named-value composition on a receiver. */
    record Member(Expr receiver, String name, Pos pos) implements Expr {
    }

    /** Application of a callee to arguments. */
    record Call(Expr callee, List<Expr> args, Pos pos) implements Expr {
    }

    /** Compile-time instantiation of a named target with argument list. */
    record Instantiate(Expr target, List<Expr> args, Pos pos) implements Expr {
    }

    /**
     * A record construction. {@code target} names the constructed type (a
     * name, path, or instantiation) and is null for an anonymous literal.
     */
    record RecordLit(Expr target, List<FieldInit> fields, Pos pos) implements Expr {
    }

    /** A list literal. */
    record ListLit(List<Expr> items, Pos pos) implements Expr {
    }

    /** A lambda abstraction; parameter types are supplied by context. */
    record Lambda(List<String> params, Expr body, Pos pos) implements Expr {
    }

    /** A match expression over a subject with ordered arms. */
    record Match(Expr subject, List<Arm> arms, Pos pos) implements Expr {
    }

    /** A binary operation. */
    record Binary(BinOp op, Expr left, Expr right, Pos pos) implements Expr {
    }

    /** A unary operation. */
    record Unary(UnOp op, Expr operand, Pos pos) implements Expr {
    }

    /** A functional record update over a base value. */
    record With(Expr base, List<FieldInit> fields, Pos pos) implements Expr {
    }

    /** One field initializer of a record construction or update. */
    record FieldInit(String name, Expr value, Pos pos) {
    }

    /** One arm of a match expression. */
    record Arm(Pattern pattern, Expr body, Pos pos) {
    }
}