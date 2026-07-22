package org.synergyst.minutiae.lang.sem;

import org.synergyst.minutiae.lang.ast.Decl;
import org.synergyst.minutiae.lang.ast.Expr;
import org.synergyst.minutiae.lang.ast.Pos;
import org.synergyst.minutiae.lang.types.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * A resolved program symbol.
 *
 * <p>All top-level declarations share one namespace; the elaborator rejects a
 * duplicate name across kinds. Local symbols (lambda parameters and template
 * parameters) live in lexical scopes and never enter the global table.
 */
public sealed interface Symbol permits Symbol.ConstSym, Symbol.SchemaSym,
        Symbol.MatrixSym, Symbol.TransformSym, Symbol.TemplateSym,
        Symbol.RuleSym, Symbol.AutomatonSym, Symbol.BuiltinFn,
        Symbol.CtorSym, Symbol.AlwaysSym, Symbol.Local {

    /** A named constant with its declared type and initializer. */
    record ConstSym(String name, Type type, Expr init, Pos pos) implements Symbol {
    }

    /** A record schema. */
    record SchemaSym(String name, Type.Rec type, Pos pos) implements Symbol {
    }

    /** A matrix with its row type and source declaration. */
    record MatrixSym(String name, Type.Rec rowType, Decl.Matrix decl, Pos pos) implements Symbol {
    }

    /** A transform with its function type and body expression. */
    record TransformSym(String name, Type.Func type, Expr body, Pos pos) implements Symbol {
    }

    /**
     * A template with its parameters, constraints, primary body, and the
     * ordered list of specialisations attached during elaboration.
     */
    final class TemplateSym implements Symbol {

        /** One typed template parameter. */
        public record Param(String name, Type type) {
        }

        /** One specialisation: matched arguments and a replacement body. */
        public record Spec(List<SpecArg> args, Expr body, Pos pos) {
        }

        /** One specialisation argument. */
        public sealed interface SpecArg permits GivenArg, AnyArg {
        }

        /** A fixed specialisation argument matched by value equality. */
        public record GivenArg(Expr value) implements SpecArg {
        }

        /** The wildcard specialisation argument. */
        public record AnyArg() implements SpecArg {
        }

        private final String name;
        private final Decl.TemplateKind kind;
        private final List<Param> params;
        private final List<Expr> constraints;
        private final Expr body;
        private final Pos pos;
        private final List<Spec> specs = new ArrayList<>(2);

        public TemplateSym(final String name, final Decl.TemplateKind kind,
                           final List<Param> params, final List<Expr> constraints,
                           final Expr body, final Pos pos) {
            this.name = name;
            this.kind = kind;
            this.params = params;
            this.constraints = constraints;
            this.body = body;
            this.pos = pos;
        }

        public String name() {
            return name;
        }

        public Decl.TemplateKind kind() {
            return kind;
        }

        public List<Param> params() {
            return params;
        }

        public List<Expr> constraints() {
            return constraints;
        }

        public Expr body() {
            return body;
        }

        public Pos pos() {
            return pos;
        }

        /** Returns the specialisations in declaration order. */
        public List<Spec> specs() {
            return specs;
        }

        /** Appends one specialisation; invoked by the elaborator only. */
        public void addSpec(final Spec spec) {
            specs.add(spec);
        }
    }

    /** A declared rule with its shape and source declaration. */
    record RuleSym(String name, Type.RuleT type, Decl.Rule decl, Pos pos) implements Symbol {
    }

    /** A declared automaton with its source declaration. */
    record AutomatonSym(String name, Decl.Automaton decl, Pos pos) implements Symbol {
    }

    /** An environment-reading built-in function. */
    record BuiltinFn(String name, Type.Func type) implements Symbol {
    }

    /** A sum constructor addressed by path. */
    record CtorSym(Type.Sum sum, Type.Ctor ctor) implements Symbol {
    }

    /** The distinguished always-true guard constant. */
    record AlwaysSym() implements Symbol {
        /** The single instance. */
        public static final AlwaysSym INSTANCE = new AlwaysSym();
    }

    /** A lexically scoped local binding. */
    record Local(String name, Type type) implements Symbol {
    }
}