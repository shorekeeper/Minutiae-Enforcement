package org.synergyst.minutiae.lang.run;

import org.synergyst.minutiae.engine.GuardEnvironment;
import org.synergyst.minutiae.lang.ast.Expr;
import org.synergyst.minutiae.lang.ast.Pattern;
import org.synergyst.minutiae.lang.eval.Env;
import org.synergyst.minutiae.lang.eval.Value;
import org.synergyst.minutiae.lang.sem.ElabTables;
import org.synergyst.minutiae.lang.sem.Symbol;
import org.synergyst.minutiae.time.DurationSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime interpreter of guard and verdict bodies.
 *
 * <p>The interpreter evaluates closure values produced by compile-time
 * normalisation against runtime arguments (event records) and a
 * {@link GuardEnvironment}. It shares the expression semantics of the
 * compile-time evaluator exactly - the same operators, the same absence of
 * implicit coercion, the same short-circuit rules - and differs in precisely
 * two capabilities: the environment built-ins ({@code precedent},
 * {@code fingerprint_score}, {@code is_online}, {@code now}) are dispatched
 * to the live environment, and template instantiations reachable from a
 * closure body are performed on demand against the elaborated template store,
 * memoised per canonical argument key.
 *
 * <p>Every dynamic failure - an arithmetic fault, an invalid text pattern, an
 * unmatched subject - raises the preallocated stackless {@link Failure}
 * sentinel. Callers on the guard path treat a failure as an unsatisfied
 * guard; callers on the verdict path treat it as a non-firing. No throwable
 * other than {@code Failure} escapes an evaluation entry point for any input
 * that passed compilation.
 *
 * <h2>Value representation at runtime</h2>
 * <p>Event facts are presented as ordinary record values whose shapes are
 * derived from the event catalogue, so field access requires no special
 * path. Account values are represented as text values carrying the canonical
 * UUID string; no operation of the language distinguishes the
 * representation, and the environment built-ins decode it at the boundary.
 *
 * <h2>Threading</h2>
 * <p>Evaluation allocates only small frame objects and is reentrant. The
 * instantiation memo is a concurrent map because dispatch may occur on the
 * server main thread and on asynchronous event threads concurrently; all
 * other state is immutable after construction. Recursion depth is bounded;
 * exceeding the bound raises {@code Failure} rather than a stack fault.
 */
public final class Interp {

    /** Stackless evaluation-failure sentinel carrying a short reason. */
    public static final class Failure extends RuntimeException {

        Failure(final String message) {
            super(message, null, false, false);
        }
    }

    private static final int MAX_DEPTH = 256;

    private final ElabTables tables;
    private final Map<String, Symbol> symbols;
    private final Map<String, Map<String, Value>> instantiationMemo = new ConcurrentHashMap<>();

    /** Per-call mutable state, confined to one evaluation entry. */
    private static final class Frame {
        final GuardEnvironment env;
        int depth;

        Frame(final GuardEnvironment env) {
            this.env = env;
        }
    }

    /**
     * Creates an interpreter over the elaboration facts of one unit.
     *
     * @param tables  the identity-keyed elaboration side tables
     * @param symbols the global symbol table, for template resolution
     */
    public Interp(final ElabTables tables, final Map<String, Symbol> symbols) {
        this.tables = tables;
        this.symbols = symbols;
    }

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /**
     * Applies a guard value to event arguments.
     *
     * @param guard the guard value: a closure or the always-true guard
     * @param args  event record arguments, one per declared event
     * @param env   the live guard environment
     * @return the guard outcome
     * @throws Failure on any evaluation fault
     */
    public boolean applyGuard(final Value guard, final List<Value> args,
                              final GuardEnvironment env) {
        final Value v = apply(guard, args, new Frame(env));
        if (v instanceof Value.BoolV b) {
            return b.value();
        }
        throw new Failure("guard did not yield a boolean");
    }

    /**
     * Applies a verdict value to event arguments.
     *
     * @param verdict the verdict closure
     * @param args    event record arguments, one per declared event
     * @param env     the live guard environment, used only for {@code now()}
     *                reachable through composed pure values
     * @return the sanction descriptor record
     * @throws Failure on any evaluation fault
     */
    public Value.RecordV applyVerdict(final Value verdict, final List<Value> args,
                                      final GuardEnvironment env) {
        final Value v = apply(verdict, args, new Frame(env));
        if (v instanceof Value.RecordV rec) {
            return rec;
        }
        throw new Failure("verdict did not yield a sanction descriptor");
    }

    // ------------------------------------------------------------------
    // Application
    // ------------------------------------------------------------------

    private Value apply(final Value fn, final List<Value> args, final Frame f) {
        if (++f.depth > MAX_DEPTH) {
            throw new Failure("evaluation depth exceeded");
        }
        try {
            return switch (fn) {
                case Value.ClosureV cl -> {
                    // Bind parameters in a child of the captured environment;
                    // the captured chain reaches the compile-time globals, so
                    // constants and transforms resolve with no extra table.
                    final Env frame = cl.env().child(args.size());
                    for (int i = 0; i < cl.params().size(); i++) {
                        frame.bind(cl.params().get(i), args.get(i));
                    }
                    yield eval(cl.body(), frame, f);
                }
                case Value.ComposeV co ->
                        apply(co.outer(), List.of(apply(co.inner(), args, f)), f);
                case Value.AlwaysV ignored -> new Value.BoolV(true);
                case Value.BuiltinV b -> builtin(b.name(), args, f);
                default -> throw new Failure("value is not applicable");
            };
        } finally {
            f.depth--;
        }
    }

    private Value builtin(final String name, final List<Value> args, final Frame f) {
        return switch (name) {
            case "precedent" -> {
                final UUID subject = accountOf(args.get(0));
                final String rule = ((Value.RuleIdV) args.get(1)).id();
                yield new Value.IntV(f.env.precedent(subject, rule));
            }
            case "fingerprint_score" ->
                    new Value.RealV(f.env.fingerprintScore(accountOf(args.get(0))));
            case "is_online" ->
                    new Value.BoolV(f.env.isOnline(accountOf(args.get(0))));
            case "now" -> new Value.IntV(f.env.now());
            default -> throw new Failure("unknown built-in '" + name + "'");
        };
    }

    /** Decodes the runtime account representation; null-safe by design. */
    private static UUID accountOf(final Value v) {
        if (v instanceof Value.TextV t && !t.value().isEmpty()) {
            try {
                return UUID.fromString(t.value());
            } catch (final IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Expression evaluation
    // ------------------------------------------------------------------

    private Value eval(final Expr e, final Env env, final Frame f) {
        return switch (e) {
            case Expr.IntLit x -> new Value.IntV(x.value());
            case Expr.RealLit x -> new Value.RealV(x.value());
            case Expr.BoolLit x -> new Value.BoolV(x.value());
            case Expr.DurLit x -> new Value.DurV(x.value());
            case Expr.TextLit x -> new Value.TextV(x.value());
            case Expr.RuleLit x -> new Value.RuleIdV(x.id());
            case Expr.NameRef n -> evalName(n, env);
            case Expr.PathRef p -> evalPath(p);
            case Expr.Member m -> evalMember(m, env, f);
            case Expr.Call c -> evalCall(c, env, f);
            case Expr.Instantiate i -> instantiate(i, env, f);
            case Expr.RecordLit lit -> evalRecordLit(lit, env, f);
            case Expr.ListLit list -> {
                final List<Value> items = new ArrayList<>(list.items().size());
                for (final Expr item : list.items()) {
                    items.add(eval(item, env, f));
                }
                yield new Value.ListV(List.copyOf(items));
            }
            case Expr.Lambda lam -> new Value.ClosureV(lam.params(), lam.body(), env,
                    (org.synergyst.minutiae.lang.types.Type.Func) tables.typeOf.get(lam));
            case Expr.Match m -> evalMatch(m, env, f);
            case Expr.Binary b -> evalBinary(b, env, f);
            case Expr.Unary u -> evalUnary(u, env, f);
            case Expr.With w -> evalWith(w, env, f);
        };
    }

    private Value evalName(final Expr.NameRef n, final Env env) {
        // Built-in functions carry no environment binding; everything else -
        // locals, parameters, constants, transforms - resolves through the
        // lexical chain captured at closure creation.
        if (tables.refOf.get(n) instanceof Symbol.BuiltinFn fn) {
            return new Value.BuiltinV(fn.name(), fn.type());
        }
        final Value v = env.lookup(n.name());
        if (v == null) {
            throw new Failure("unbound name '" + n.name() + "'");
        }
        return v;
    }

    private Value evalPath(final Expr.PathRef p) {
        return switch (tables.refOf.get(p)) {
            case Symbol.AlwaysSym ignored -> Value.AlwaysV.INSTANCE;
            case Symbol.CtorSym c ->
                    new Value.VariantV(c.sum(), c.ctor().name(), List.of());
            case null, default -> throw new Failure("unresolved path value");
        };
    }

    private Value evalMember(final Expr.Member m, final Env env, final Frame f) {
        if (tables.memberKind.get(m) == ElabTables.MemberKind.COMPOSE) {
            final Value outer = eval(m.receiver(), env, f);
            final Value inner = env.lookup(m.name());
            if (inner == null) {
                throw new Failure("unresolved composition operand '" + m.name() + "'");
            }
            return new Value.ComposeV(outer, inner);
        }
        final Value recv = eval(m.receiver(), env, f);
        if (recv instanceof Value.RecordV rec) {
            final Value field = rec.field(m.name());
            if (field != null) {
                return field;
            }
        }
        throw new Failure("field access failed on '." + m.name() + "'");
    }

    private Value evalCall(final Expr.Call c, final Env env, final Frame f) {
        // Positional variant construction is a value build, not a call.
        if (c.callee() instanceof Expr.PathRef p
                && tables.refOf.get(p) instanceof Symbol.CtorSym ctor
                && !ctor.ctor().fields().isEmpty()) {
            final List<Value> fields = new ArrayList<>(c.args().size());
            for (final Expr arg : c.args()) {
                fields.add(eval(arg, env, f));
            }
            return new Value.VariantV(ctor.sum(), ctor.ctor().name(), List.copyOf(fields));
        }
        final Value fn = eval(c.callee(), env, f);
        final List<Value> args = new ArrayList<>(c.args().size());
        for (final Expr arg : c.args()) {
            args.add(eval(arg, env, f));
        }
        return apply(fn, args, f);
    }

    // ------------------------------------------------------------------
    // Template instantiation at dispatch time
    // ------------------------------------------------------------------

    private Value instantiate(final Expr.Instantiate i, final Env env, final Frame f) {
        if (!(tables.refOf.get(i) instanceof Symbol.TemplateSym tpl)) {
            throw new Failure("unresolved template instantiation");
        }
        // Arguments are pure by elaboration; evaluate and derive the memo key.
        final List<Value> args = new ArrayList<>(i.args().size());
        final StringBuilder keyB = new StringBuilder(32);
        for (final Expr arg : i.args()) {
            final Value v = eval(arg, env, f);
            args.add(v);
            keyB.append(Value.key(v)).append('|');
        }
        final String key = keyB.toString();
        final Map<String, Value> memo = instantiationMemo
                .computeIfAbsent(tpl.name(), n -> new ConcurrentHashMap<>(4));
        final Value cached = memo.get(key);
        if (cached != null) {
            return cached;
        }
        // Parameters bind over the closure's lexical chain so the template
        // body sees the same globals it was elaborated against.
        final Env frame = env.child(args.size());
        for (int k = 0; k < tpl.params().size(); k++) {
            frame.bind(tpl.params().get(k).name(), args.get(k));
        }
        // Primary constraints govern every instantiation, specialised or not.
        for (final Expr constraint : tpl.constraints()) {
            final Value v = eval(constraint, frame, f);
            if (!(v instanceof Value.BoolV b) || !b.value()) {
                throw new Failure("template '" + tpl.name() + "' constraint not satisfied");
            }
        }
        final Expr body = selectBody(tpl, args, env, f);
        final Value result = eval(body, frame, f);
        memo.put(key, result);
        return result;
    }

    private Expr selectBody(final Symbol.TemplateSym tpl, final List<Value> args,
                            final Env env, final Frame f) {
        for (final Symbol.TemplateSym.Spec spec : tpl.specs()) {
            boolean matches = true;
            for (int k = 0; k < spec.args().size() && matches; k++) {
                if (spec.args().get(k) instanceof Symbol.TemplateSym.GivenArg g) {
                    // Fixed arguments are constant expressions; evaluating
                    // them under the current chain is sound because they
                    // reference globals only.
                    matches = Value.equal(eval(g.value(), env, f), args.get(k));
                }
            }
            if (matches) {
                return spec.body();
            }
        }
        return tpl.body();
    }

    // ------------------------------------------------------------------
    // Constructions, match, operators
    // ------------------------------------------------------------------

    private Value evalRecordLit(final Expr.RecordLit lit, final Env env, final Frame f) {
        final ElabTables.Constructed made = tables.ctorOf.get(lit);
        final Map<String, Value> byName = new HashMap<>(lit.fields().size() * 2);
        for (final Expr.FieldInit fi : lit.fields()) {
            byName.put(fi.name(), eval(fi.value(), env, f));
        }
        return switch (made) {
            case ElabTables.RecC r -> {
                final List<Value> ordered = new ArrayList<>(r.type().fields().size());
                for (final var field : r.type().fields()) {
                    ordered.add(byName.get(field.name()));
                }
                yield new Value.RecordV(r.type(), List.copyOf(ordered));
            }
            case ElabTables.VariantC v -> {
                final List<Value> ordered = new ArrayList<>(v.ctor().fields().size());
                for (final var field : v.ctor().fields()) {
                    ordered.add(byName.get(field.name()));
                }
                yield new Value.VariantV(v.sum(), v.ctor().name(), List.copyOf(ordered));
            }
            case ElabTables.StepC s -> new Value.StepV(s.type().event(), byName.get("guard"));
            // A rule construction cannot occur inside a guard or verdict body
            // by typing; reaching it here is an internal inconsistency.
            case ElabTables.RuleC ignored ->
                    throw new Failure("rule construction in a runtime body");
        };
    }

    private Value evalWith(final Expr.With w, final Env env, final Frame f) {
        final Value base = eval(w.base(), env, f);
        if (!(base instanceof Value.RecordV rec)) {
            throw new Failure("'with' on a non-record value");
        }
        final List<Value> fields = new ArrayList<>(rec.fields());
        for (final Expr.FieldInit fi : w.fields()) {
            fields.set(rec.type().fieldIndex(fi.name()), eval(fi.value(), env, f));
        }
        return new Value.RecordV(rec.type(), List.copyOf(fields));
    }

    private Value evalMatch(final Expr.Match m, final Env env, final Frame f) {
        final Value subject = eval(m.subject(), env, f);
        for (final Expr.Arm arm : m.arms()) {
            if (matches(arm.pattern(), subject)) {
                return eval(arm.body(), env, f);
            }
        }
        // Exhaustiveness was proven at elaboration over the subject's type;
        // an unmatched value indicates internal inconsistency, not user error.
        throw new Failure("no match arm accepted the subject");
    }

    private static boolean matches(final Pattern p, final Value v) {
        return switch (p) {
            case Pattern.WildPat ignored -> true;
            case Pattern.IntPat ip -> v instanceof Value.IntV iv && iv.value() == ip.value();
            case Pattern.RangePat rp -> v instanceof Value.IntV iv
                    && iv.value() >= rp.lo() && iv.value() <= rp.hi();
            case Pattern.PathPat pp -> v instanceof Value.VariantV vv
                    && vv.ctor().equals(pp.segments().get(1));
        };
    }

    private Value evalBinary(final Expr.Binary b, final Env env, final Frame f) {
        // Short-circuit forms evaluate the right operand conditionally.
        switch (b.op()) {
            case AND -> {
                if (!((Value.BoolV) eval(b.left(), env, f)).value()) {
                    return new Value.BoolV(false);
                }
                return eval(b.right(), env, f);
            }
            case OR -> {
                if (((Value.BoolV) eval(b.left(), env, f)).value()) {
                    return new Value.BoolV(true);
                }
                return eval(b.right(), env, f);
            }
            default -> {
                // Strict forms fall through to two-operand evaluation.
            }
        }
        final Value l = eval(b.left(), env, f);
        final Value r = eval(b.right(), env, f);
        return switch (b.op()) {
            case EQ -> new Value.BoolV(Value.equal(l, r));
            case NE -> new Value.BoolV(!Value.equal(l, r));
            case MATCHES -> {
                try {
                    yield new Value.BoolV(java.util.regex.Pattern
                            .compile(((Value.TextV) r).value())
                            .matcher(((Value.TextV) l).value()).find());
                } catch (final java.util.regex.PatternSyntaxException ex) {
                    throw new Failure("invalid text pattern");
                }
            }
            case LT, GT, LE, GE -> new Value.BoolV(compare(l, r, b.op()));
            case ADD -> arith(l, r, '+');
            case SUB -> arith(l, r, '-');
            case MUL -> multiply(l, r);
            case DIV -> divide(l, r);
            case AND, OR -> throw new Failure("unreachable");
        };
    }

    private static boolean compare(final Value l, final Value r,
                                   final org.synergyst.minutiae.lang.ast.BinOp op) {
        final int c;
        if (l instanceof Value.IntV a && r instanceof Value.IntV b) {
            c = Long.compare(a.value(), b.value());
        } else if (l instanceof Value.RealV a && r instanceof Value.RealV b) {
            c = Double.compare(a.value(), b.value());
        } else if (l instanceof Value.DurV a && r instanceof Value.DurV b) {
            c = Long.compare(a.value().millis(), b.value().millis());
        } else {
            throw new Failure("incomparable values");
        }
        return switch (op) {
            case LT -> c < 0;
            case GT -> c > 0;
            case LE -> c <= 0;
            case GE -> c >= 0;
            default -> false;
        };
    }

    private static Value arith(final Value l, final Value r, final char op) {
        if (l instanceof Value.IntV a && r instanceof Value.IntV b) {
            try {
                return new Value.IntV(op == '+'
                        ? Math.addExact(a.value(), b.value())
                        : Math.subtractExact(a.value(), b.value()));
            } catch (final ArithmeticException ex) {
                throw new Failure("integer overflow");
            }
        }
        if (l instanceof Value.RealV a && r instanceof Value.RealV b) {
            return new Value.RealV(op == '+' ? a.value() + b.value() : a.value() - b.value());
        }
        if (l instanceof Value.DurV a && r instanceof Value.DurV b) {
            final long ms = op == '+'
                    ? a.value().millis() + b.value().millis()
                    : a.value().millis() - b.value().millis();
            if (ms < 0L) {
                throw new Failure("negative duration");
            }
            return new Value.DurV(new DurationSpec(ms, false));
        }
        throw new Failure("invalid arithmetic operands");
    }

    private static Value multiply(final Value l, final Value r) {
        if (l instanceof Value.IntV a && r instanceof Value.IntV b) {
            try {
                return new Value.IntV(Math.multiplyExact(a.value(), b.value()));
            } catch (final ArithmeticException ex) {
                throw new Failure("integer overflow");
            }
        }
        if (l instanceof Value.RealV a && r instanceof Value.RealV b) {
            return new Value.RealV(a.value() * b.value());
        }
        final Value.DurV dur;
        final Value.IntV factor;
        if (l instanceof Value.DurV d && r instanceof Value.IntV n) {
            dur = d;
            factor = n;
        } else if (l instanceof Value.IntV n && r instanceof Value.DurV d) {
            dur = d;
            factor = n;
        } else {
            throw new Failure("invalid multiplication operands");
        }
        if (factor.value() < 0L) {
            throw new Failure("negative duration factor");
        }
        try {
            return new Value.DurV(dur.value().times(factor.value()));
        } catch (final ArithmeticException ex) {
            throw new Failure("duration overflow");
        }
    }

    private static Value divide(final Value l, final Value r) {
        if (l instanceof Value.IntV a && r instanceof Value.IntV b) {
            if (b.value() == 0L) {
                throw new Failure("division by zero");
            }
            return new Value.IntV(a.value() / b.value());
        }
        if (l instanceof Value.RealV a && r instanceof Value.RealV b) {
            return new Value.RealV(a.value() / b.value());
        }
        throw new Failure("invalid division operands");
    }

    private Value evalUnary(final Expr.Unary u, final Env env, final Frame f) {
        final Value v = eval(u.operand(), env, f);
        return switch (u.op()) {
            case NOT -> new Value.BoolV(!((Value.BoolV) v).value());
            case NEG -> switch (v) {
                case Value.IntV x -> {
                    try {
                        yield new Value.IntV(Math.negateExact(x.value()));
                    } catch (final ArithmeticException ex) {
                        throw new Failure("integer overflow");
                    }
                }
                case Value.RealV x -> new Value.RealV(-x.value());
                default -> throw new Failure("invalid negation operand");
            };
        };
    }
}