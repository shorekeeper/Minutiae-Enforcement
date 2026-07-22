package org.synergyst.minutiae.lang.sem;

import org.synergyst.minutiae.lang.ast.*;
import org.synergyst.minutiae.lang.diag.DiagnosticSink;
import org.synergyst.minutiae.lang.types.Builtins;
import org.synergyst.minutiae.lang.types.Effect;
import org.synergyst.minutiae.lang.types.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.synergyst.minutiae.lang.types.Type.*;

/**
 * Elaborator of the definition language.
 *
 * <p>Declarations are processed in source order under a declaration-before-use
 * discipline: a name is visible from the point of its declaration onward, and
 * a forward reference is an unknown-name error. This discipline makes cyclic
 * definitions unrepresentable and removes any need for dependency resolution.
 *
 * <p>Type checking is bidirectional. {@code infer} synthesises a type from an
 * expression; {@code check} pushes an expected type into forms that require
 * context (lambdas, anonymous record literals, empty lists, match results,
 * the always-guard constant) and verifies assignability elsewhere. No
 * implicit coercion exists on any path.
 *
 * <p>Effect checking threads an allowance through every position. Constant
 * initializers, matrix cells, trigger expressions, template constraints,
 * specialisation arguments, transform bodies, and expansion arguments allow
 * {@link Effect#PURE}; guard bodies allow {@link Effect#QUERY}; verdict
 * bodies allow {@link Effect#PURE}. A lambda body is granted the effect of
 * the function type it is checked against, so a step guard constructed inside
 * a pure trigger expression is itself query-capable while its construction
 * remains pure.
 *
 * <p>Match analysis enforces totality. Over an integer subject a wildcard arm
 * is mandatory. Over a sum subject whose constructors are all nullary, either
 * every constructor is covered or a wildcard is present. Arms following a
 * wildcard, duplicate integer or constructor arms, and range patterns with an
 * inverted bound are rejected. Subjects of any other type are rejected.
 *
 * <p>A declaration that fails elaboration is reported and abandoned; its name
 * remains bound when the header elaborated, limiting cascade errors. Control
 * flow inside the elaborator uses a preallocated stackless sentinel; no
 * throwable escapes {@link #run}.
 */
public final class Elaborator {

    /** Stackless control-flow sentinel; carries no message and no trace. */
    private static final class Abort extends RuntimeException {
        static final Abort INSTANCE = new Abort();

        private Abort() {
            super(null, null, false, false);
        }
    }

    /** A lexical scope of local bindings. */
    private static final class Scope {
        final Scope parent;
        final Map<String, Symbol.Local> locals = new HashMap<>(4);

        Scope(final Scope parent) {
            this.parent = parent;
        }

        Symbol.Local find(final String name) {
            for (Scope s = this; s != null; s = s.parent) {
                final Symbol.Local l = s.locals.get(name);
                if (l != null) {
                    return l;
                }
            }
            return null;
        }
    }

    private final DiagnosticSink diags;
    private final ElabTables tables = new ElabTables();
    private final LinkedHashMap<String, Symbol> symbols = new LinkedHashMap<>(32);
    private final List<Decl> accepted = new ArrayList<>();

    private Elaborator(final DiagnosticSink diagnostics) {
        this.diags = diagnostics;
    }

    /**
     * Elaborates one compilation unit.
     *
     * @param decls       parsed declarations, in source order
     * @param diagnostics the sink receiving semantic diagnostics
     * @return the elaboration result over the accepted declarations
     */
    public static ElabResult run(final List<Decl> decls, final DiagnosticSink diagnostics) {
        final Elaborator e = new Elaborator(diagnostics);
        for (final Decl d : decls) {
            try {
                e.declare(d);
                e.accepted.add(d);
            } catch (final Abort a) {
                // The declaration was reported; siblings continue.
            }
        }
        return new ElabResult(List.copyOf(e.accepted), e.symbols, e.tables);
    }

    // ------------------------------------------------------------------
    // Declarations
    // ------------------------------------------------------------------

    private void declare(final Decl d) {
        switch (d) {
            case Decl.Const c -> constDecl(c);
            case Decl.Schema s -> schemaDecl(s);
            case Decl.Matrix m -> matrixDecl(m);
            case Decl.Transform t -> transformDecl(t);
            case Decl.Expand x -> expandDecl(x);
            case Decl.Template t -> templateDecl(t);
            case Decl.TemplateSpec s -> templateSpecDecl(s);
            case Decl.Rule r -> ruleDecl(r);
            case Decl.Automaton a -> automatonDecl(a);
        }
    }

    private void constDecl(final Decl.Const c) {
        final Type t = resolveType(c.type());
        define(c.name(), new Symbol.ConstSym(c.name(), t, c.init(), c.pos()), c.pos());
        check(c.init(), t, new Scope(null), Effect.PURE);
    }

    private void schemaDecl(final Decl.Schema s) {
        final List<Field> fields = new ArrayList<>(s.fields().size());
        final Set<String> seen = new HashSet<>(8);
        for (final Decl.Schema.Field f : s.fields()) {
            if (!seen.add(f.name())) {
                error("E014", f.pos(), "duplicate field '" + f.name() + "' in schema '" + s.name() + "'");
                throw Abort.INSTANCE;
            }
            fields.add(new Field(f.name(), resolveType(f.type())));
        }
        if (Builtins.typeNamed(s.name()) != null) {
            error("E014", s.pos(), "schema name '" + s.name() + "' collides with a built-in type");
            throw Abort.INSTANCE;
        }
        final Rec rec = new Rec(s.name(), List.copyOf(fields));
        define(s.name(), new Symbol.SchemaSym(s.name(), rec, s.pos()), s.pos());
    }

    private void matrixDecl(final Decl.Matrix m) {
        final Type schemaT = resolveType(m.schema());
        if (!(schemaT instanceof Rec row)) {
            error("E002", m.schema().pos(),
                    "matrix schema must be a record schema, not " + show(schemaT));
            throw Abort.INSTANCE;
        }
        define(m.name(), new Symbol.MatrixSym(m.name(), row, m, m.pos()), m.pos());
        final Scope empty = new Scope(null);
        for (final Decl.Matrix.Row r : m.rows()) {
            if (r.cells().size() != row.fields().size()) {
                error("E008", r.pos(), "row has " + r.cells().size()
                        + " cell(s); schema '" + row.name() + "' declares "
                        + row.fields().size() + " field(s)");
                continue;
            }
            for (int i = 0; i < r.cells().size(); i++) {
                check(r.cells().get(i), row.fields().get(i).type(), empty, Effect.PURE);
            }
        }
    }

    private void transformDecl(final Decl.Transform t) {
        final Type from = resolveType(t.from());
        final Type to = resolveType(t.to());
        final Func fn = new Func(List.of(from), to, Effect.PURE);
        define(t.name(), new Symbol.TransformSym(t.name(), fn, t.body(), t.pos()), t.pos());
        check(t.body(), fn, new Scope(null), Effect.PURE);
    }

    private void expandDecl(final Decl.Expand x) {
        if (!(x.application() instanceof Expr.Call call) || call.args().size() != 1) {
            error("E023", x.pos(), "'expand' requires a single-argument transform application");
            throw Abort.INSTANCE;
        }
        final Scope empty = new Scope(null);
        final Type calleeT = infer(call.callee(), empty, Effect.PURE);
        if (!(calleeT instanceof Func fn) || fn.params().size() != 1
                || fn.effect() != Effect.PURE) {
            error("E023", call.callee().pos(),
                    "expansion target must be a pure single-parameter transform, not "
                            + show(calleeT));
            throw Abort.INSTANCE;
        }
        final Type produced = fn.result();
        final boolean layout = same(produced, Builtins.LAYOUT);
        final boolean rule = produced instanceof RuleT;
        if (!layout && !rule) {
            error("E023", call.callee().pos(),
                    "expansion must produce Layout or Rule values, not " + show(produced));
            throw Abort.INSTANCE;
        }
        check(call.args().get(0), new ListT(fn.params().get(0)), empty, Effect.PURE);
        tables.typeOf.put(call, produced);
    }

    private void templateDecl(final Decl.Template t) {
        final List<Symbol.TemplateSym.Param> params = new ArrayList<>(t.params().size());
        final Scope scope = new Scope(null);
        for (final Decl.Template.Param p : t.params()) {
            final Type pt = resolveType(p.type());
            if (!admissibleTemplateParam(pt)) {
                error("E028", p.pos(), "type " + show(pt)
                        + " is not admissible as a template parameter; admissible types are"
                        + " Measure, Duration, Int, Text, Rule");
                throw Abort.INSTANCE;
            }
            if (scope.locals.put(p.name(), new Symbol.Local(p.name(), pt)) != null) {
                error("E014", p.pos(), "duplicate template parameter '" + p.name() + "'");
                throw Abort.INSTANCE;
            }
            params.add(new Symbol.TemplateSym.Param(p.name(), pt));
        }
        final Symbol.TemplateSym sym = new Symbol.TemplateSym(
                t.name(), t.kind(), List.copyOf(params), t.constraints(), t.body(), t.pos());
        define(t.name(), sym, t.pos());

        for (final Expr c : t.constraints()) {
            check(c, BOOL, scope, Effect.PURE);
        }
        check(t.body(), templateBodyType(t.kind()), scope, Effect.PURE);
    }

    private void templateSpecDecl(final Decl.TemplateSpec s) {
        final Symbol sym = symbols.get(s.name());
        if (!(sym instanceof Symbol.TemplateSym primary)) {
            error("E001", s.pos(), "specialisation of unknown template '" + s.name() + "'");
            throw Abort.INSTANCE;
        }
        if (primary.kind() != s.kind()) {
            error("E017", s.pos(), "specialisation kind does not match template '"
                    + s.name() + "'");
            throw Abort.INSTANCE;
        }
        if (s.args().size() != primary.params().size()) {
            error("E018", s.pos(), "specialisation supplies " + s.args().size()
                    + " argument(s); template declares " + primary.params().size());
            throw Abort.INSTANCE;
        }
        final Scope empty = new Scope(null);
        final List<Symbol.TemplateSym.SpecArg> args = new ArrayList<>(s.args().size());
        for (int i = 0; i < s.args().size(); i++) {
            switch (s.args().get(i)) {
                case Decl.TemplateSpec.Any any ->
                        args.add(new Symbol.TemplateSym.AnyArg());
                case Decl.TemplateSpec.Given g -> {
                    check(g.value(), primary.params().get(i).type(), empty, Effect.PURE);
                    args.add(new Symbol.TemplateSym.GivenArg(g.value()));
                }
            }
        }
        // The specialisation body sees every primary parameter by name; the
        // instantiation binds actual values to all of them, matched or not.
        final Scope scope = new Scope(null);
        for (final Symbol.TemplateSym.Param p : primary.params()) {
            scope.locals.put(p.name(), new Symbol.Local(p.name(), p.type()));
        }
        check(s.body(), templateBodyType(s.kind()), scope, Effect.PURE);
        primary.addSpec(new Symbol.TemplateSym.Spec(List.copyOf(args), s.body(), s.pos()));
    }

    private void ruleDecl(final Decl.Rule r) {
        final List<Event> events = new ArrayList<>(r.events().size());
        for (final String name : r.events()) {
            final Event ev = Builtins.event(name);
            if (ev == null) {
                error("E025", r.pos(), "unknown event type '" + name + "'");
                throw Abort.INSTANCE;
            }
            events.add(ev);
        }
        final RuleT shape = new RuleT(List.copyOf(events));
        define(r.name(), new Symbol.RuleSym(r.name(), shape, r, r.pos()), r.pos());

        final Scope empty = new Scope(null);
        check(r.trigger(), Builtins.TRIGGER, empty, Effect.PURE);
        check(r.guard(), guardType(events), empty, Effect.PURE);
        check(r.verdict(), verdictType(events), empty, Effect.PURE);
    }

    private void automatonDecl(final Decl.Automaton a) {
        if (a.parent() != null && !(symbols.get(a.parent()) instanceof Symbol.AutomatonSym)) {
            error("E001", a.pos(), "unknown parent automaton '" + a.parent() + "'");
            throw Abort.INSTANCE;
        }
        define(a.name(), new Symbol.AutomatonSym(a.name(), a, a.pos()), a.pos());
        for (final Decl.Automaton.Item item : a.items()) {
            switch (item) {
                case Decl.Automaton.Use u -> requireRule(u.rule(), u.pos());
                case Decl.Automaton.Override o -> {
                    final Event ev = Builtins.event(o.event());
                    if (ev == null) {
                        error("E025", o.pos(), "unknown event type '" + o.event() + "'");
                        throw Abort.INSTANCE;
                    }
                    final Symbol.RuleSym rs = requireRule(o.rule(), o.pos());
                    if (!rs.type().events().get(0).name().equals(o.event())) {
                        error("E022", o.pos(), "rule '" + o.rule()
                                + "' is bound to event '" + rs.type().events().get(0).name()
                                + "' and cannot replace rules on '" + o.event() + "'");
                        throw Abort.INSTANCE;
                    }
                }
            }
        }
    }

    private Symbol.RuleSym requireRule(final String name, final Pos pos) {
        if (symbols.get(name) instanceof Symbol.RuleSym rs) {
            return rs;
        }
        error("E022", pos, "'" + name + "' does not name a rule");
        throw Abort.INSTANCE;
    }

    // ------------------------------------------------------------------
    // Type resolution
    // ------------------------------------------------------------------

    private Type resolveType(final TypeRef ref) {
        return switch (ref) {
            case TypeRef.ArrayOf a -> new ListT(resolveType(a.element()));
            case TypeRef.Named n -> {
                if (n.name().equals("Rule") && !n.args().isEmpty()) {
                    final List<Event> events = new ArrayList<>(n.args().size());
                    for (final TypeRef arg : n.args()) {
                        if (!(arg instanceof TypeRef.Named en) || !en.args().isEmpty()
                                || Builtins.event(en.name()) == null) {
                            error("E025", arg.pos(), "Rule type arguments must be event names");
                            throw Abort.INSTANCE;
                        }
                        events.add(Builtins.event(en.name()));
                    }
                    yield new RuleT(List.copyOf(events));
                }
                if (!n.args().isEmpty()) {
                    error("E002", n.pos(), "type '" + n.name() + "' takes no arguments");
                    throw Abort.INSTANCE;
                }
                final Type builtin = Builtins.typeNamed(n.name());
                if (builtin != null) {
                    yield builtin;
                }
                if (symbols.get(n.name()) instanceof Symbol.SchemaSym s) {
                    yield s.type();
                }
                error("E002", n.pos(), "unknown type '" + n.name() + "'");
                throw Abort.INSTANCE;
            }
        };
    }

    private static boolean admissibleTemplateParam(final Type t) {
        if (same(t, Builtins.MEASURE)) {
            return true;
        }
        return t instanceof Prim p && Builtins.TEMPLATE_PRIM_PARAMS.contains(p.kind());
    }

    private static Func templateBodyType(final Decl.TemplateKind kind) {
        return kind == Decl.TemplateKind.VERDICT
                ? new Func(List.of(Builtins.ANY_EVENT), Builtins.SANCTION, Effect.PURE)
                : new Func(List.of(Builtins.ANY_EVENT), BOOL, Effect.QUERY);
    }

    private static Func guardType(final List<Event> events) {
        return new Func(List.copyOf(events), BOOL, Effect.QUERY);
    }

    private static Func verdictType(final List<Event> events) {
        return new Func(List.copyOf(events), Builtins.SANCTION, Effect.PURE);
    }

    // ------------------------------------------------------------------
    // Bidirectional expression checking
    // ------------------------------------------------------------------

    /**
     * Checks an expression against an expected type under a scope and effect
     * allowance, recording the elaborated type.
     */
    private void check(final Expr e, final Type expected, final Scope sc, final Effect allowed) {
        switch (e) {
            case Expr.Lambda lam -> {
                if (!(expected instanceof Func fn)) {
                    error("E003", e.pos(), "a lambda cannot produce " + show(expected));
                    throw Abort.INSTANCE;
                }
                checkLambda(lam, fn, sc);
            }
            case Expr.RecordLit lit when lit.target() == null ->
                    checkRecordLit(lit, expected, sc, allowed);
            case Expr.ListLit list -> {
                if (!(expected instanceof ListT lt)) {
                    error("E003", e.pos(), "a list literal cannot produce " + show(expected));
                    throw Abort.INSTANCE;
                }
                for (final Expr item : list.items()) {
                    check(item, lt.elem(), sc, allowed);
                }
                tables.typeOf.put(e, expected);
            }
            case Expr.Match m -> {
                checkMatch(m, expected, sc, allowed);
            }
            default -> {
                final Type actual = infer(e, sc, allowed);
                if (!assignable(actual, expected)) {
                    error("E003", e.pos(), "expected " + show(expected)
                            + " but the expression has type " + show(actual));
                    throw Abort.INSTANCE;
                }
            }
        }
    }

    private void checkLambda(final Expr.Lambda lam, final Func fn, final Scope outer) {
        if (lam.params().size() != fn.params().size()) {
            error("E008", lam.pos(), "lambda declares " + lam.params().size()
                    + " parameter(s); the position expects " + fn.params().size());
            throw Abort.INSTANCE;
        }
        final Scope sc = new Scope(outer);
        for (int i = 0; i < lam.params().size(); i++) {
            final String p = lam.params().get(i);
            if (sc.locals.put(p, new Symbol.Local(p, fn.params().get(i))) != null) {
                error("E014", lam.pos(), "duplicate lambda parameter '" + p + "'");
                throw Abort.INSTANCE;
            }
        }
        // The body receives exactly the effect the function type grants.
        check(lam.body(), fn.result(), sc, fn.effect());
        tables.typeOf.put(lam, fn);
        tables.lambdaParams.put(lam, fn.params());
    }

    /**
     * Infers a type for an expression under a scope and effect allowance,
     * recording the elaborated type.
     */
    private Type infer(final Expr e, final Scope sc, final Effect allowed) {
        final Type t = inferInner(e, sc, allowed);
        tables.typeOf.put(e, t);
        return t;
    }

    private Type inferInner(final Expr e, final Scope sc, final Effect allowed) {
        return switch (e) {
            case Expr.IntLit ignored -> INT;
            case Expr.RealLit ignored -> REAL;
            case Expr.BoolLit ignored -> BOOL;
            case Expr.DurLit ignored -> DURATION;
            case Expr.TextLit ignored -> TEXT;
            case Expr.RuleLit ignored -> RULE;
            case Expr.NameRef n -> inferName(n, sc);
            case Expr.PathRef p -> inferPath(p);
            case Expr.Member m -> inferMember(m, sc, allowed);
            case Expr.Call c -> inferCall(c, sc, allowed);
            case Expr.Instantiate i -> inferInstantiate(i, sc, allowed);
            case Expr.RecordLit lit -> inferRecordLit(lit, sc, allowed);
            case Expr.ListLit list -> inferList(list, sc, allowed);
            case Expr.Match m -> inferMatch(m, sc, allowed);
            case Expr.Binary b -> inferBinary(b, sc, allowed);
            case Expr.Unary u -> inferUnary(u, sc, allowed);
            case Expr.With w -> inferWith(w, sc, allowed);
            case Expr.Lambda l -> {
                error("E009", l.pos(),
                        "a lambda requires an expected function type from its position");
                throw Abort.INSTANCE;
            }
        };
    }

    private Type inferName(final Expr.NameRef n, final Scope sc) {
        final Symbol.Local local = sc.find(n.name());
        if (local != null) {
            tables.refOf.put(n, local);
            return local.type();
        }
        final Symbol global = symbols.get(n.name());
        if (global != null) {
            tables.refOf.put(n, global);
            return switch (global) {
                case Symbol.ConstSym c -> c.type();
                case Symbol.TransformSym t -> t.type();
                case Symbol.MatrixSym m -> new ListT(m.rowType());
                case Symbol.TemplateSym t -> {
                    error("E017", n.pos(), "template '" + t.name()
                            + "' must be instantiated with '<...>' arguments");
                    throw Abort.INSTANCE;
                }
                default -> {
                    error("E036", n.pos(), "'" + n.name() + "' does not denote a value");
                    throw Abort.INSTANCE;
                }
            };
        }
        final Func builtin = Builtins.function(n.name());
        if (builtin != null) {
            tables.refOf.put(n, new Symbol.BuiltinFn(n.name(), builtin));
            return builtin;
        }
        error("E001", n.pos(), "unknown name '" + n.name() + "'");
        throw Abort.INSTANCE;
    }

    private Type inferPath(final Expr.PathRef p) {
        if (p.segments().size() == 2 && p.segments().get(0).equals("Guard")
                && p.segments().get(1).equals("Always")) {
            tables.refOf.put(p, Symbol.AlwaysSym.INSTANCE);
            return new Func(List.of(Builtins.ANY_EVENT), BOOL, Effect.QUERY);
        }
        if (p.segments().size() != 2) {
            error("E001", p.pos(), "unknown path '" + String.join("::", p.segments()) + "'");
            throw Abort.INSTANCE;
        }
        final Sum sum = Builtins.sumByRoot(p.segments().get(0));
        final Ctor ctor = sum == null ? null : sum.ctor(p.segments().get(1));
        if (ctor == null) {
            error("E001", p.pos(), "unknown path '" + String.join("::", p.segments()) + "'");
            throw Abort.INSTANCE;
        }
        tables.refOf.put(p, new Symbol.CtorSym(sum, ctor));
        if (!ctor.fields().isEmpty()) {
            error("E035", p.pos(), "constructor '" + sum.name() + "::" + ctor.name()
                    + "' requires arguments");
            throw Abort.INSTANCE;
        }
        return sum;
    }

    private Type inferMember(final Expr.Member m, final Scope sc, final Effect allowed) {
        final Type recv = infer(m.receiver(), sc, allowed);
        switch (recv) {
            case Rec r -> {
                final int idx = r.fieldIndex(m.name());
                if (idx >= 0) {
                    tables.memberKind.put(m, ElabTables.MemberKind.FIELD);
                    return r.fields().get(idx).type();
                }
                error("E015", m.pos(), "record " + r.name() + " has no field '" + m.name() + "'");
                throw Abort.INSTANCE;
            }
            case Event ev -> {
                final Type ft = ev.field(m.name());
                if (ft != null) {
                    tables.memberKind.put(m, ElabTables.MemberKind.FIELD);
                    return ft;
                }
                error("E015", m.pos(), "event " + ev.name() + " has no field '" + m.name() + "'");
                throw Abort.INSTANCE;
            }
            case Func outer when outer.params().size() == 1
                    && outer.effect() == Effect.PURE -> {
                // Composition: the member names a pure single-parameter
                // function whose result feeds the receiver's parameter.
                final Symbol sym = symbols.get(m.name());
                final Func inner = switch (sym) {
                    case Symbol.TransformSym t -> t.type();
                    case Symbol.ConstSym c when c.type() instanceof Func f -> f;
                    case null, default -> null;
                };
                if (inner == null || inner.params().size() != 1
                        || inner.effect() != Effect.PURE) {
                    error("E016", m.pos(), "'" + m.name()
                            + "' does not name a pure single-parameter function for composition");
                    throw Abort.INSTANCE;
                }
                if (!assignable(inner.result(), outer.params().get(0))) {
                    error("E016", m.pos(), "composition mismatch: '" + m.name()
                            + "' produces " + show(inner.result())
                            + " but the receiver consumes " + show(outer.params().get(0)));
                    throw Abort.INSTANCE;
                }
                tables.memberKind.put(m, ElabTables.MemberKind.COMPOSE);
                tables.refOf.put(m, sym);
                return new Func(inner.params(), outer.result(), Effect.PURE);
            }
            default -> {
                error("E015", m.pos(), "type " + show(recv) + " has no members");
                throw Abort.INSTANCE;
            }
        }
    }

    private Type inferCall(final Expr.Call c, final Scope sc, final Effect allowed) {
        // Positional construction of a sum variant with fields.
        if (c.callee() instanceof Expr.PathRef p && p.segments().size() == 2) {
            final Sum sum = Builtins.sumByRoot(p.segments().get(0));
            final Ctor ctor = sum == null ? null : sum.ctor(p.segments().get(1));
            if (ctor != null && !ctor.fields().isEmpty()) {
                tables.refOf.put(p, new Symbol.CtorSym(sum, ctor));
                tables.typeOf.put(p, sum);
                if (c.args().size() != ctor.fields().size()) {
                    error("E008", c.pos(), "constructor '" + sum.name() + "::" + ctor.name()
                            + "' takes " + ctor.fields().size() + " argument(s), got "
                            + c.args().size());
                    throw Abort.INSTANCE;
                }
                for (int i = 0; i < c.args().size(); i++) {
                    check(c.args().get(i), ctor.fields().get(i).type(), sc, allowed);
                }
                return sum;
            }
        }
        final Type calleeT = infer(c.callee(), sc, allowed);
        if (!(calleeT instanceof Func fn)) {
            error("E037", c.pos(), "type " + show(calleeT) + " is not callable");
            throw Abort.INSTANCE;
        }
        if (!fn.effect().le(allowed)) {
            error("E007", c.pos(), "a query-capable function cannot be called in a pure position");
            throw Abort.INSTANCE;
        }
        if (c.args().size() != fn.params().size()) {
            error("E008", c.pos(), "call supplies " + c.args().size()
                    + " argument(s); the function takes " + fn.params().size());
            throw Abort.INSTANCE;
        }
        for (int i = 0; i < c.args().size(); i++) {
            check(c.args().get(i), fn.params().get(i), sc, allowed);
        }
        return fn.result();
    }

    private Type inferInstantiate(final Expr.Instantiate i, final Scope sc, final Effect allowed) {
        if (!(i.target() instanceof Expr.NameRef n)) {
            error("E017", i.pos(), "only a named template may be instantiated");
            throw Abort.INSTANCE;
        }
        if (n.name().equals("Step") || n.name().equals("Rule")) {
            error("E006", i.pos(), "'" + n.name()
                    + "<...>' is valid only as a record construction target");
            throw Abort.INSTANCE;
        }
        if (!(symbols.get(n.name()) instanceof Symbol.TemplateSym tpl)) {
            error("E001", i.pos(), "unknown template '" + n.name() + "'");
            throw Abort.INSTANCE;
        }
        tables.refOf.put(i, tpl);
        if (i.args().size() != tpl.params().size()) {
            error("E008", i.pos(), "template '" + tpl.name() + "' takes "
                    + tpl.params().size() + " argument(s), got " + i.args().size());
            throw Abort.INSTANCE;
        }
        for (int k = 0; k < i.args().size(); k++) {
            check(i.args().get(k), tpl.params().get(k).type(), sc, Effect.PURE);
        }
        return templateBodyType(tpl.kind());
    }

    private Type inferRecordLit(final Expr.RecordLit lit, final Scope sc, final Effect allowed) {
        if (lit.target() == null) {
            error("E006", lit.pos(),
                    "an anonymous record literal requires an expected record type");
            throw Abort.INSTANCE;
        }
        final ElabTables.Constructed made = resolveConstruct(lit.target(), lit.pos());
        tables.ctorOf.put(lit, made);
        return switch (made) {
            case ElabTables.RecC r -> {
                checkFields(lit, r.type().fields(), sc, allowed);
                yield r.type();
            }
            case ElabTables.VariantC v -> {
                checkFields(lit, v.ctor().fields(), sc, allowed);
                yield v.sum();
            }
            case ElabTables.StepC s -> {
                final Func guardT = new Func(List.of(s.type().event()), BOOL, Effect.QUERY);
                checkFields(lit, List.of(new Field("guard", guardT)), sc, allowed);
                yield s.type();
            }
            case ElabTables.RuleC r -> {
                checkFields(lit, List.of(
                        new Field("trigger", Builtins.TRIGGER),
                        new Field("guard", guardType(r.type().events())),
                        new Field("verdict", verdictType(r.type().events()))), sc, allowed);
                yield r.type();
            }
        };
    }

    private void checkRecordLit(final Expr.RecordLit lit, final Type expected,
                                final Scope sc, final Effect allowed) {
        if (!(expected instanceof Rec rec)) {
            error("E006", lit.pos(), "an anonymous record literal cannot produce "
                    + show(expected));
            throw Abort.INSTANCE;
        }
        tables.ctorOf.put(lit, new ElabTables.RecC(rec));
        tables.typeOf.put(lit, rec);
        checkFields(lit, rec.fields(), sc, allowed);
    }

    private ElabTables.Constructed resolveConstruct(final Expr target, final Pos pos) {
        switch (target) {
            case Expr.NameRef n -> {
                if (n.name().equals("Sanction")) {
                    return new ElabTables.RecC(Builtins.SANCTION);
                }
                if (n.name().equals("Layout")) {
                    return new ElabTables.RecC(Builtins.LAYOUT);
                }
                if (symbols.get(n.name()) instanceof Symbol.SchemaSym s) {
                    return new ElabTables.RecC(s.type());
                }
            }
            case Expr.PathRef p when p.segments().size() == 2 -> {
                final Sum sum = Builtins.sumByRoot(p.segments().get(0));
                final Ctor ctor = sum == null ? null : sum.ctor(p.segments().get(1));
                if (ctor != null) {
                    return new ElabTables.VariantC(sum, ctor);
                }
            }
            case Expr.Instantiate i when i.target() instanceof Expr.NameRef n -> {
                if (n.name().equals("Step") && i.args().size() == 1
                        && i.args().get(0) instanceof Expr.NameRef ev
                        && Builtins.event(ev.name()) != null) {
                    return new ElabTables.StepC(new Step(Builtins.event(ev.name())));
                }
                if (n.name().equals("Rule") && !i.args().isEmpty()) {
                    final List<Event> events = new ArrayList<>(i.args().size());
                    for (final Expr arg : i.args()) {
                        if (!(arg instanceof Expr.NameRef en)
                                || Builtins.event(en.name()) == null) {
                            error("E025", arg.pos(), "Rule arguments must be event names");
                            throw Abort.INSTANCE;
                        }
                        events.add(Builtins.event(en.name()));
                    }
                    return new ElabTables.RuleC(new RuleT(List.copyOf(events)));
                }
            }
            default -> {
            }
        }
        error("E006", pos, "the expression does not name a constructible type");
        throw Abort.INSTANCE;
    }

    private void checkFields(final Expr.RecordLit lit, final List<Field> fields,
                             final Scope sc, final Effect allowed) {
        final Set<String> supplied = new HashSet<>(lit.fields().size() * 2);
        for (final Expr.FieldInit f : lit.fields()) {
            supplied.add(f.name());
            Field decl = null;
            for (final Field df : fields) {
                if (df.name().equals(f.name())) {
                    decl = df;
                    break;
                }
            }
            if (decl == null) {
                error("E004", f.pos(), "unknown field '." + f.name() + "'");
                throw Abort.INSTANCE;
            }
            check(f.value(), decl.type(), sc, allowed);
        }
        final List<String> missing = new ArrayList<>(0);
        for (final Field df : fields) {
            if (!supplied.contains(df.name())) {
                missing.add("." + df.name());
            }
        }
        if (!missing.isEmpty()) {
            error("E005", lit.pos(), "construction is missing mandatory field(s): "
                    + String.join(", ", missing));
            throw Abort.INSTANCE;
        }
    }

    private Type inferList(final Expr.ListLit list, final Scope sc, final Effect allowed) {
        if (list.items().isEmpty()) {
            error("E033", list.pos(), "an empty list literal requires an expected element type");
            throw Abort.INSTANCE;
        }
        final Type elem = infer(list.items().get(0), sc, allowed);
        for (int i = 1; i < list.items().size(); i++) {
            check(list.items().get(i), elem, sc, allowed);
        }
        return new ListT(elem);
    }

    private Type inferWith(final Expr.With w, final Scope sc, final Effect allowed) {
        final Type base = infer(w.base(), sc, allowed);
        if (!(base instanceof Rec rec)) {
            error("E003", w.pos(), "'with' requires a record value, not " + show(base));
            throw Abort.INSTANCE;
        }
        for (final Expr.FieldInit f : w.fields()) {
            final int idx = rec.fieldIndex(f.name());
            if (idx < 0) {
                error("E004", f.pos(), "record " + rec.name() + " has no field '."
                        + f.name() + "'");
                throw Abort.INSTANCE;
            }
            check(f.value(), rec.fields().get(idx).type(), sc, allowed);
        }
        return rec;
    }

    // ------------------------------------------------------------------
    // Match analysis
    // ------------------------------------------------------------------

    private Type inferMatch(final Expr.Match m, final Scope sc, final Effect allowed) {
        final Type subject = infer(m.subject(), sc, allowed);
        validatePatterns(m, subject);
        Type result = null;
        for (final Expr.Arm arm : m.arms()) {
            if (result == null) {
                result = infer(arm.body(), sc, allowed);
            } else {
                check(arm.body(), result, sc, allowed);
            }
        }
        return result;
    }

    private void checkMatch(final Expr.Match m, final Type expected,
                            final Scope sc, final Effect allowed) {
        final Type subject = infer(m.subject(), sc, allowed);
        validatePatterns(m, subject);
        for (final Expr.Arm arm : m.arms()) {
            check(arm.body(), expected, sc, allowed);
        }
        tables.typeOf.put(m, expected);
    }

    private void validatePatterns(final Expr.Match m, final Type subject) {
        final boolean overInt = same(subject, INT);
        final Sum overSum = subject instanceof Sum s ? s : null;
        if (!overInt && overSum == null) {
            error("E010", m.pos(), "match subjects must be Int or an enumerated sum type, not "
                    + show(subject));
            throw Abort.INSTANCE;
        }
        boolean wildcardSeen = false;
        final Set<Long> ints = new HashSet<>();
        final Set<String> ctors = new HashSet<>();
        for (final Expr.Arm arm : m.arms()) {
            if (wildcardSeen) {
                error("E012", arm.pos(), "arm is unreachable: it follows a wildcard arm");
                throw Abort.INSTANCE;
            }
            switch (arm.pattern()) {
                case Pattern.WildPat ignored -> wildcardSeen = true;
                case Pattern.IntPat p -> {
                    requireInt(overInt, arm.pattern().pos());
                    if (!ints.add(p.value())) {
                        error("E012", p.pos(), "duplicate integer arm " + p.value());
                        throw Abort.INSTANCE;
                    }
                }
                case Pattern.RangePat p -> {
                    requireInt(overInt, arm.pattern().pos());
                    if (p.lo() > p.hi()) {
                        error("E013", p.pos(), "range bounds are inverted: "
                                + p.lo() + " > " + p.hi());
                        throw Abort.INSTANCE;
                    }
                }
                case Pattern.PathPat p -> {
                    if (overSum == null || p.segments().size() != 2
                            || !p.segments().get(0).equals(overSum.name())) {
                        error("E013", p.pos(), "pattern is incompatible with subject type "
                                + show(subject));
                        throw Abort.INSTANCE;
                    }
                    final Ctor c = overSum.ctor(p.segments().get(1));
                    if (c == null || !c.fields().isEmpty()) {
                        error("E013", p.pos(), "pattern must name a nullary constructor of "
                                + overSum.name());
                        throw Abort.INSTANCE;
                    }
                    if (!ctors.add(c.name())) {
                        error("E012", p.pos(), "duplicate constructor arm "
                                + overSum.name() + "::" + c.name());
                        throw Abort.INSTANCE;
                    }
                }
            }
        }
        if (overInt && !wildcardSeen) {
            error("E011", m.pos(), "a match over Int requires a wildcard arm");
            throw Abort.INSTANCE;
        }
        if (overSum != null && !wildcardSeen) {
            for (final Ctor c : overSum.ctors()) {
                if (!c.fields().isEmpty() || !ctors.contains(c.name())) {
                    error("E011", m.pos(), "match over " + overSum.name()
                            + " is not exhaustive; add a wildcard arm or cover every"
                            + " nullary constructor");
                    throw Abort.INSTANCE;
                }
            }
        }
    }

    private void requireInt(final boolean overInt, final Pos pos) {
        if (!overInt) {
            error("E013", pos, "an integer pattern requires an Int subject");
            throw Abort.INSTANCE;
        }
    }

    // ------------------------------------------------------------------
    // Operators
    // ------------------------------------------------------------------

    private Type inferBinary(final Expr.Binary b, final Scope sc, final Effect allowed) {
        return switch (b.op()) {
            case AND, OR -> {
                check(b.left(), BOOL, sc, allowed);
                check(b.right(), BOOL, sc, allowed);
                yield BOOL;
            }
            case MATCHES -> {
                check(b.left(), TEXT, sc, allowed);
                check(b.right(), TEXT, sc, allowed);
                yield BOOL;
            }
            case EQ, NE -> {
                final Type lt = infer(b.left(), sc, allowed);
                final Type rt = infer(b.right(), sc, allowed);
                if (!same(lt, rt) || !equalityComparable(lt)) {
                    error("E032", b.pos(), "equality is not defined between "
                            + show(lt) + " and " + show(rt));
                    throw Abort.INSTANCE;
                }
                yield BOOL;
            }
            case LT, GT, LE, GE -> {
                final Type lt = infer(b.left(), sc, allowed);
                final Type rt = infer(b.right(), sc, allowed);
                if (!same(lt, rt) || !orderedType(lt)) {
                    error("E019", b.pos(), "ordering is not defined between "
                            + show(lt) + " and " + show(rt));
                    throw Abort.INSTANCE;
                }
                yield BOOL;
            }
            case ADD, SUB -> arithSame(b, sc, allowed, true);
            case MUL -> {
                final Type lt = infer(b.left(), sc, allowed);
                final Type rt = infer(b.right(), sc, allowed);
                if (same(lt, DURATION) && same(rt, INT)) {
                    yield DURATION;
                }
                if (same(lt, INT) && same(rt, DURATION)) {
                    yield DURATION;
                }
                if (same(lt, rt) && (same(lt, INT) || same(lt, REAL))) {
                    yield lt;
                }
                error("E019", b.pos(), "'*' is not defined between "
                        + show(lt) + " and " + show(rt));
                throw Abort.INSTANCE;
            }
            case DIV -> {
                final Type lt = infer(b.left(), sc, allowed);
                final Type rt = infer(b.right(), sc, allowed);
                if (same(lt, rt) && (same(lt, INT) || same(lt, REAL))) {
                    yield lt;
                }
                error("E019", b.pos(), "'/' is not defined between "
                        + show(lt) + " and " + show(rt));
                throw Abort.INSTANCE;
            }
        };
    }

    private Type arithSame(final Expr.Binary b, final Scope sc, final Effect allowed,
                           final boolean durationsToo) {
        final Type lt = infer(b.left(), sc, allowed);
        final Type rt = infer(b.right(), sc, allowed);
        final boolean ok = same(lt, rt)
                && (same(lt, INT) || same(lt, REAL) || (durationsToo && same(lt, DURATION)));
        if (!ok) {
            error("E019", b.pos(), "the operator is not defined between "
                    + show(lt) + " and " + show(rt));
            throw Abort.INSTANCE;
        }
        return lt;
    }

    private Type inferUnary(final Expr.Unary u, final Scope sc, final Effect allowed) {
        return switch (u.op()) {
            case NOT -> {
                check(u.operand(), BOOL, sc, allowed);
                yield BOOL;
            }
            case NEG -> {
                final Type t = infer(u.operand(), sc, allowed);
                if (!same(t, INT) && !same(t, REAL)) {
                    error("E019", u.pos(), "negation is not defined on " + show(t));
                    throw Abort.INSTANCE;
                }
                yield t;
            }
        };
    }

    private static boolean equalityComparable(final Type t) {
        if (t instanceof Sum) {
            return true;
        }
        return t instanceof Prim p && p.kind() != PrimKind.ACCOUNT;
    }

    private static boolean orderedType(final Type t) {
        return t instanceof Prim p
                && (p.kind() == PrimKind.INT || p.kind() == PrimKind.REAL
                || p.kind() == PrimKind.DURATION);
    }

    // ------------------------------------------------------------------
    // Symbol table
    // ------------------------------------------------------------------

    private void define(final String name, final Symbol sym, final Pos pos) {
        if (symbols.putIfAbsent(name, sym) != null) {
            error("E014", pos, "duplicate definition of '" + name + "'");
            throw Abort.INSTANCE;
        }
    }

    private void error(final String code, final Pos pos, final String message) {
        diags.error(code, pos.line(), pos.column(), message);
    }
}