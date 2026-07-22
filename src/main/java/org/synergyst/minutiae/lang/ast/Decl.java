package org.synergyst.minutiae.lang.ast;

import java.util.List;

/**
 * A top-level declaration.
 *
 * <p>The declaration set is closed. Every declaration introduces a named,
 * immutable, compile-time value, with two exceptions that introduce sets of
 * values: {@link Expand} contributes the results of applying a transform to a
 * matrix, and {@link Automaton} contributes a named rule set assembled by set
 * algebra over previously declared rules.
 */
public sealed interface Decl permits Decl.Const, Decl.Schema, Decl.Matrix,
        Decl.Transform, Decl.Expand, Decl.Template, Decl.TemplateSpec,
        Decl.Rule, Decl.Automaton {

    /** Returns the declaration's source position. */
    Pos pos();

    /** Returns the declared name, or null for declarations without one. */
    String name();

    /**
     * A named constant of a declared type.
     *
     * @param type declared type
     * @param name constant name
     * @param init initializer expression
     * @param pos  source position
     */
    record Const(TypeRef type, String name, Expr init, Pos pos) implements Decl {
    }

    /**
     * A record schema: an ordered list of typed fields.
     *
     * @param name   schema name
     * @param fields ordered fields
     * @param pos    source position
     */
    record Schema(String name, List<Field> fields, Pos pos) implements Decl {

        /** One schema field. */
        public record Field(TypeRef type, String name, Pos pos) {
        }
    }

    /**
     * A matrix: an ordered table of rows conforming to a schema.
     *
     * @param schema row schema reference
     * @param name   matrix name
     * @param rows   ordered rows, each a positional cell list
     * @param pos    source position
     */
    record Matrix(TypeRef schema, String name, List<Row> rows, Pos pos) implements Decl {

        /** One matrix row. */
        public record Row(List<Expr> cells, Pos pos) {
        }
    }

    /**
     * A transform: a total pure compile-time function from one type to
     * another, denoted by its body expression.
     *
     * @param from source type
     * @param to   target type
     * @param name transform name
     * @param body body expression, normally a lambda
     * @param pos  source position
     */
    record Transform(TypeRef from, TypeRef to, String name, Expr body, Pos pos) implements Decl {
    }

    /**
     * An expansion: application of a transform over a matrix, contributing
     * the produced declarations to the compilation unit.
     *
     * @param application the application expression; verified to be a call
     * @param pos         source position
     */
    record Expand(Expr application, Pos pos) implements Decl {

        @Override
        public String name() {
            return null;
        }
    }

    /**
     * A primary template producing a guard or verdict value per
     * instantiation.
     *
     * @param params      typed parameters, at least one
     * @param constraints constraint expressions over the parameters,
     *                    possibly empty
     * @param kind        produced value kind
     * @param name        template name
     * @param body        body expression
     * @param pos         source position
     */
    record Template(List<Param> params, List<Expr> constraints, TemplateKind kind,
                    String name, Expr body, Pos pos) implements Decl {

        /** One typed template parameter. */
        public record Param(TypeRef type, String name, Pos pos) {
        }
    }

    /**
     * A template specialisation selected by argument matching. A wildcard
     * argument admits any value in its position; a given argument must equal
     * the instantiation argument for the specialisation to apply.
     *
     * @param kind produced value kind, matching the primary
     * @param name specialised template name
     * @param args ordered specialisation arguments
     * @param body body used when the arguments match
     * @param pos  source position
     */
    record TemplateSpec(TemplateKind kind, String name, List<SpecArg> args,
                        Expr body, Pos pos) implements Decl {

        /** A specialisation argument: a fixed expression or the wildcard. */
        public sealed interface SpecArg permits Given, Any {
        }

        /** A fixed specialisation argument. */
        public record Given(Expr value) implements SpecArg {
        }

        /** The wildcard specialisation argument. */
        public record Any(Pos pos) implements SpecArg {
        }
    }

    /** The kind of value a template produces. */
    enum TemplateKind {
        VERDICT, GUARD
    }

    /**
     * A rule: one implication from a condition to a verdict over one or more
     * event types.
     *
     * <p>The condition comprises exactly one trigger expression and exactly
     * one guard expression; the verdict is exactly one expression. All three
     * are mandatory by grammar; totality of the produced descriptor is
     * enforced by later phases.
     *
     * @param events  event type names, at least one
     * @param name    rule name
     * @param trigger trigger expression
     * @param guard   guard expression
     * @param verdict verdict expression
     * @param pos     source position
     */
    record Rule(List<String> events, String name, Expr trigger, Expr guard,
                Expr verdict, Pos pos) implements Decl {
    }

    /**
     * An automaton: a named rule set, optionally derived from a parent by
     * union, with per-event replacement expressed by override items.
     *
     * @param name   automaton name
     * @param parent parent automaton name, or null for a root
     * @param items  membership items in source order
     * @param pos    source position
     */
    record Automaton(String name, String parent, List<Item> items, Pos pos) implements Decl {

        /** One membership item. */
        public sealed interface Item permits Use, Override {
        }

        /** Inclusion of a named rule. */
        public record Use(String rule, Pos pos) implements Item {
        }

        /**
         * Replacement of every inherited rule bound to an event type by a
         * named rule.
         */
        public record Override(String event, String rule, Pos pos) implements Item {
        }
    }
}