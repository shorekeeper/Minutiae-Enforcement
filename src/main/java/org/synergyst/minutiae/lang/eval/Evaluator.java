package org.synergyst.minutiae.lang.eval;

import org.synergyst.minutiae.lang.ast.Decl;
import org.synergyst.minutiae.lang.ast.Expr;
import org.synergyst.minutiae.lang.ast.Pattern;
import org.synergyst.minutiae.lang.ast.Pos;
import org.synergyst.minutiae.lang.diag.DiagnosticSink;
import org.synergyst.minutiae.lang.sem.ElabResult;
import org.synergyst.minutiae.lang.sem.ElabTables;
import org.synergyst.minutiae.lang.sem.Symbol;
import org.synergyst.minutiae.lang.types.Builtins;
import org.synergyst.minutiae.lang.types.Type;
import org.synergyst.minutiae.time.DurationSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.PatternSyntaxException;

/**
 * Compile-time evaluator of an elaborated compilation unit.
 *
 * <p>Declarations are evaluated in source order against a monotonically
 * growing root environment. Constant, matrix, and transform declarations bind
 * their values by name. Template declarations remain symbolic; every
 * instantiation site evaluates its arguments, selects the first matching
 * specialisation in declaration order (a fixed argument matches by structural
 * value equality, the wildcard matches anything), verifies every primary
 * constraint under the bound parameters, and evaluates the selected body in a
 * child environment binding all parameters; results are memoised per template
 * by canonical argument key. Rule declarations reduce their trigger
 * expressions to data, validate the trigger shape against the declared event
 * list, and capture guard and verdict values. Expansion declarations apply a
 * transform value element-wise over a matrix value, collecting produced
 * layout descriptors and rules; layout keys are checked for global
 * uniqueness. Automaton declarations resolve to ordered rule lists: the
 * parent's resolved list is copied, each override first removes every
 * inherited rule whose first event equals the named event and then inserts
 * its replacement, and each use appends.
 *
 * <p>Guard and verdict bodies are captured, never executed. All arithmetic is
 * checked: integer overflow, division by zero, negative duration results, and
 * negative duration scale factors are positioned errors. Application depth is
 * bounded; exceeding the bound is an error rather than a stack fault.
 *
 * <p>A declaration that fails evaluation is reported and skipped; siblings
 * evaluate normally. No throwable escapes {@link #run}.
 */
public final class Evaluator {

    private static final int MAX_DEPTH = 512;

    /** Stackless control-flow sentinel; carries no message and no trace. */
    private static final class Abort extends RuntimeException {
        static final Abort INSTANCE = new Abort();

        private Abort() {
            super(null, null, false, false);
        }
    }

    /** Residual artifacts of one evaluated compilation unit. */
    public record Output(Map<String, Value> constants,
                         List<Value.RecordV> layouts,
                         LinkedHashMap<String, Value.RuleV> rules,
                         List<Value.RuleV> expandedRules,
                         LinkedHashMap<String, List<Value.RuleV>> automata) {
    }

    private final ElabTables tables;
    private final DiagnosticSink diags;
    private final Env global = Env.root();

    private final Map<String, Value> constants = new LinkedHashMap<>();
    private final List<Value.RecordV> layouts = new ArrayList<>();
    private final Set<String> layoutKeys = new HashSet<>();
    private final LinkedHashMap<String, Value.RuleV> rules = new LinkedHashMap<>();
    private final List<Value.RuleV> expandedRules = new ArrayList<>();
    private final LinkedHashMap<String, List<Value.RuleV>> automata = new LinkedHashMap<>();
    private final Map<String, Map<String, Value>> instantiationMemo = new HashMap<>();

    private int depth;

    private final Map<String, Symbol> symbols;

    private Evaluator(final ElabResult elab, final DiagnosticSink diagnostics) {
        this.tables = elab.tables();
        this.symbols = elab.symbols();
        this.diags = diagnostics;
    }

    // ------------------------------------------------------------------
    // Declarations
    // ------------------------------------------------------------------

    private void evalDecl(final Decl d) {
        switch (d) {
            case Decl.Const c -> {
                final Value v = eval(c.init(), global);
                constants.put(c.name(), v);
                global.bind(c.name(), v);
            }
            case Decl.Schema ignored -> {
                // A schema contributes a type only.
            }
            case Decl.Matrix m -> {
                final Type.Rec row = ((Symbol.MatrixSym) requireSymbol(m.name())).rowType();
                final List<Value> items = new ArrayList<>(m.rows().size());
                for (final Decl.Matrix.Row r : m.rows()) {
                    final List<Value> cells = new ArrayList<>(r.cells().size());
                    for (final Expr cell : r.cells()) {
                        cells.add(eval(cell, global));
                    }
                    items.add(new Value.RecordV(row, List.copyOf(cells)));
                }
                global.bind(m.name(), new Value.ListV(List.copyOf(items)));
            }
            case Decl.Transform t -> global.bind(t.name(), eval(t.body(), global));
            case Decl.Template ignored -> {
                // Instantiated on demand.
            }
            case Decl.TemplateSpec ignored -> {
                // Attached to its primary during elaboration.
            }
            case Decl.Rule r -> {
                final Symbol.RuleSym sym = (Symbol.RuleSym) requireSymbol(r.name());
                final Value.RuleV rv = buildRule(r.name(), sym.type().events(),
                        r.trigger(), r.guard(), r.verdict(), r.pos());
                rules.put(r.name(), rv);
            }
            case Decl.Expand x -> evalExpand(x);
            case Decl.Automaton a -> evalAutomaton(a);
        }
    }

    private Value.RuleV buildRule(final String name, final List<Type.Event> events,
                                  final Expr triggerE, final Expr guardE,
                                  final Expr verdictE, final Pos pos) {
        final Value triggerV = eval(triggerE, global);
        if (!(triggerV instanceof Value.VariantV trigger)
                || !trigger.sum().name().equals(Builtins.TRIGGER.name())) {
            error("V001", pos, "the trigger expression did not reduce to a trigger value");
            throw Abort.INSTANCE;
        }
        validateTrigger(trigger, events, pos);
        final Value guard = eval(guardE, global);
        final Value verdict = eval(verdictE, global);
        return new Value.RuleV(name, events, trigger, guard, verdict);
    }

    private void validateTrigger(final Value.VariantV trigger, final List<Type.Event> events,
                                 final Pos pos) {
        switch (trigger.ctor()) {
            case "Atomic" -> {
                if (events.size() != 1) {
                    error("V001", pos, "an atomic trigger observes exactly one event; the rule"
                            + " declares " + events.size());
                    throw Abort.INSTANCE;
                }
            }
            case "Repeated" -> {
                if (events.size() != 1) {
                    error("V001", pos, "a repeated trigger observes exactly one event; the rule"
                            + " declares " + events.size());
                    throw Abort.INSTANCE;
                }
                final long count = ((Value.IntV) trigger.field(0)).value();
                if (count < 1 || count > 10_000) {
                    error("V001", pos, "recurrence count " + count
                            + " is outside the supported range [1, 10000]");
                    throw Abort.INSTANCE;
                }
                requirePositiveSpan(((Value.DurV) trigger.field(1)).value(), pos);
            }
            case "Sequence" -> {
                requirePositiveSpan(((Value.DurV) trigger.field(0)).value(), pos);
                final List<Value> steps = ((Value.ListV) trigger.field(2)).items();
                if (steps.size() < 2) {
                    error("V002", pos, "a sequence trigger requires at least two steps");
                    throw Abort.INSTANCE;
                }
                if (steps.size() != events.size()) {
                    error("V002", pos, "the sequence declares " + steps.size()
                            + " step(s); the rule declares " + events.size() + " event(s)");
                    throw Abort.INSTANCE;
                }
                for (int i = 0; i < steps.size(); i++) {
                    final Value.StepV step = (Value.StepV) steps.get(i);
                    if (!step.event().name().equals(events.get(i).name())) {
                        error("V002", pos, "step " + (i + 1) + " observes '"
                                + step.event().name() + "' but the rule declares '"
                                + events.get(i).name() + "' at that position");
                        throw Abort.INSTANCE;
                    }
                }
            }
            default -> {
                error("V001", pos, "unrecognised trigger constructor '" + trigger.ctor() + "'");
                throw Abort.INSTANCE;
            }
        }
    }

    private void requirePositiveSpan(final DurationSpec d, final Pos pos) {
        if (d.permanent() || d.millis() <= 0L) {
            error("V001", pos, "the window span must be a positive finite duration");
            throw Abort.INSTANCE;
        }
    }

    private void evalExpand(final Decl.Expand x) {
        final Expr.Call call = (Expr.Call) x.application();
        final Value fn = eval(call.callee(), global);
        final Value.ListV arg = (Value.ListV) eval(call.args().get(0), global);
        final String base = expansionBaseName(call.callee());
        int index = 0;
        for (final Value row : arg.items()) {
            final Value produced = apply(fn, List.of(row), x.pos());
            switch (produced) {
                case Value.RecordV rec when rec.type().name().equals(Builtins.LAYOUT.name()) -> {
                    final String key = ((Value.TextV) rec.field("key")).value();
                    if (!layoutKeys.add(key)) {
                        error("V016", x.pos(), "duplicate layout key '" + key
                                + "' produced by expansion");
                        continue;
                    }
                    layouts.add(rec);
                }
                case Value.RuleV rv -> expandedRules.add(rv.named(base + "#" + index));
                default -> {
                    error("V013", x.pos(), "expansion produced an unexpected value kind");
                    throw Abort.INSTANCE;
                }
            }
            index++;
        }
    }

    private static String expansionBaseName(final Expr callee) {
        Expr e = callee;
        while (e instanceof Expr.Member m) {
            e = m.receiver();
        }
        return e instanceof Expr.NameRef n ? n.name() : "expand";
    }

    private void evalAutomaton(final Decl.Automaton a) {
        final LinkedHashMap<String, Value.RuleV> current = new LinkedHashMap<>();
        if (a.parent() != null) {
            final List<Value.RuleV> parent = automata.get(a.parent());
            if (parent != null) {
                for (final Value.RuleV r : parent) {
                    current.put(r.name(), r);
                }
            }
        }
        for (final Decl.Automaton.Item item : a.items()) {
            switch (item) {
                case Decl.Automaton.Use u -> {
                    final Value.RuleV r = rules.get(u.rule());
                    if (current.putIfAbsent(u.rule(), r) != null) {
                        error("V017", u.pos(), "rule '" + u.rule()
                                + "' is already a member of automaton '" + a.name() + "'");
                        throw Abort.INSTANCE;
                    }
                }
                case Decl.Automaton.Override o -> {
                    int removed = 0;
                    final var it = current.entrySet().iterator();
                    while (it.hasNext()) {
                        if (it.next().getValue().events().get(0).name().equals(o.event())) {
                            it.remove();
                            removed++;
                        }
                    }
                    if (removed == 0) {
                        diags.warning("W001", o.pos().line(), o.pos().column(),
                                "override on '" + o.event() + "' removed no inherited rule");
                    }
                    current.put(o.rule(), rules.get(o.rule()));
                }
            }
        }
        automata.put(a.name(), List.copyOf(current.values()));
    }

    // ------------------------------------------------------------------
    // Expression evaluation
    // ------------------------------------------------------------------

    private Value eval(final Expr e, final Env env) {
        return switch (e) {
            case Expr.IntLit x -> new Value.IntV(x.value());
            case Expr.RealLit x -> new Value.RealV(x.value());
            case Expr.BoolLit x -> new Value.BoolV(x.value());
            case Expr.DurLit x -> new Value.DurV(x.value());
            case Expr.TextLit x -> new Value.TextV(x.value());
            case Expr.RuleLit x -> new Value.RuleIdV(x.id());
            case Expr.NameRef n -> evalName(n, env);
            case Expr.PathRef p -> evalPath(p);
            case Expr.Member m -> evalMember(m, env);
            case Expr.Call c -> evalCall(c, env);
            case Expr.Instantiate i -> instantiate(i, env);
            case Expr.RecordLit lit -> evalRecordLit(lit, env);
            case Expr.ListLit list -> {
                final List<Value> items = new ArrayList<>(list.items().size());
                for (final Expr item : list.items()) {
                    items.add(eval(item, env));
                }
                yield new Value.ListV(List.copyOf(items));
            }
            case Expr.Lambda lam -> new Value.ClosureV(lam.params(), lam.body(), env,
                    (Type.Func) tables.typeOf.get(lam));
            case Expr.Match m -> evalMatch(m, env);
            case Expr.Binary b -> evalBinary(b, env);
            case Expr.Unary u -> evalUnary(u, env);
            case Expr.With w -> evalWith(w, env);
        };
    }

    private Value evalName(final Expr.NameRef n, final Env env) {
        final Symbol sym = tables.refOf.get(n);
        return switch (sym) {
            case Symbol.Local l -> env.lookup(l.name());
            case Symbol.BuiltinFn f -> new Value.BuiltinV(f.name(), f.type());
            case null, default -> global.lookup(n.name());
        };
    }

    private Value evalPath(final Expr.PathRef p) {
        final Symbol sym = tables.refOf.get(p);
        return switch (sym) {
            case Symbol.AlwaysSym ignored -> Value.AlwaysV.INSTANCE;
            case Symbol.CtorSym c -> new Value.VariantV(c.sum(), c.ctor().name(), List.of());
            case null, default -> {
                error("V013", p.pos(), "unresolved path value");
                throw Abort.INSTANCE;
            }
        };
    }

    private Value evalMember(final Expr.Member m, final Env env) {
        final ElabTables.MemberKind kind = tables.memberKind.get(m);
        if (kind == ElabTables.MemberKind.COMPOSE) {
            final Value outer = eval(m.receiver(), env);
            final Symbol sym = tables.refOf.get(m);
            final Value inner = switch (sym) {
                case Symbol.TransformSym t -> global.lookup(t.name());
                case Symbol.ConstSym c -> global.lookup(c.name());
                case null, default -> null;
            };
            if (inner == null) {
                error("V013", m.pos(), "unresolved composition operand");
                throw Abort.INSTANCE;
            }
            return new Value.ComposeV(outer, inner);
        }
        final Value recv = eval(m.receiver(), env);
        if (recv instanceof Value.RecordV rec) {
            return rec.field(m.name());
        }
        error("V013", m.pos(), "field access on a non-record compile-time value");
        throw Abort.INSTANCE;
    }

    private Value evalCall(final Expr.Call c, final Env env) {
        // Positional variant construction.
        if (c.callee() instanceof Expr.PathRef p
                && tables.refOf.get(p) instanceof Symbol.CtorSym ctor
                && !ctor.ctor().fields().isEmpty()) {
            final List<Value> fields = new ArrayList<>(c.args().size());
            for (final Expr arg : c.args()) {
                fields.add(eval(arg, env));
            }
            return new Value.VariantV(ctor.sum(), ctor.ctor().name(), List.copyOf(fields));
        }
        final Value fn = eval(c.callee(), env);
        final List<Value> args = new ArrayList<>(c.args().size());
        for (final Expr arg : c.args()) {
            args.add(eval(arg, env));
        }
        return apply(fn, args, c.pos());
    }

    /**
     * Applies an applicable value to arguments with a bounded depth.
     *
     * @param fn   the applicable value
     * @param args the arguments
     * @param pos  the application position for diagnostics
     * @return the application result
     */
    private Value apply(final Value fn, final List<Value> args, final Pos pos) {
        if (++depth > MAX_DEPTH) {
            depth--;
            error("V012", pos, "evaluation depth exceeded " + MAX_DEPTH);
            throw Abort.INSTANCE;
        }
        try {
            return switch (fn) {
                case Value.ClosureV cl -> {
                    final Env frame = cl.env().child(args.size());
                    for (int i = 0; i < cl.params().size(); i++) {
                        frame.bind(cl.params().get(i), args.get(i));
                    }
                    yield eval(cl.body(), frame);
                }
                case Value.ComposeV co -> apply(co.outer(),
                        List.of(apply(co.inner(), args, pos)), pos);
                case Value.AlwaysV ignored -> new Value.BoolV(true);
                default -> {
                    error("V013", pos, "the value is not applicable at compile time");
                    throw Abort.INSTANCE;
                }
            };
        } finally {
            depth--;
        }
    }

    // ------------------------------------------------------------------
    // Template instantiation
    // ------------------------------------------------------------------

    private Value instantiate(final Expr.Instantiate i, final Env env) {
        final Symbol.TemplateSym tpl = (Symbol.TemplateSym) tables.refOf.get(i);
        final List<Value> args = new ArrayList<>(i.args().size());
        final StringBuilder keyB = new StringBuilder(32);
        for (final Expr arg : i.args()) {
            final Value v = eval(arg, env);
            args.add(v);
            keyB.append(Value.key(v)).append('|');
        }
        final String key = keyB.toString();
        final Map<String, Value> memo =
                instantiationMemo.computeIfAbsent(tpl.name(), n -> new HashMap<>(4));
        final Value cached = memo.get(key);
        if (cached != null) {
            return cached;
        }

        final Env frame = global.child(args.size());
        for (int k = 0; k < tpl.params().size(); k++) {
            frame.bind(tpl.params().get(k).name(), args.get(k));
        }
        // Primary constraints govern every instantiation, specialised or not.
        for (final Expr constraint : tpl.constraints()) {
            final Value v = eval(constraint, frame);
            if (!(v instanceof Value.BoolV b) || !b.value()) {
                error("V003", constraint.pos(), "template '" + tpl.name()
                        + "' constraint is not satisfied by the instantiation at "
                        + i.pos().line() + ":" + i.pos().column());
                throw Abort.INSTANCE;
            }
        }
        final Expr body = selectBody(tpl, args);
        final Value result = eval(body, frame);
        memo.put(key, result);
        return result;
    }

    private Expr selectBody(final Symbol.TemplateSym tpl, final List<Value> args) {
        for (final Symbol.TemplateSym.Spec spec : tpl.specs()) {
            boolean matches = true;
            for (int k = 0; k < spec.args().size(); k++) {
                switch (spec.args().get(k)) {
                    case Symbol.TemplateSym.AnyArg ignored -> {
                        // Matches any value.
                    }
                    case Symbol.TemplateSym.GivenArg g -> {
                        final Value fixed = eval(g.value(), global);
                        if (!Value.equal(fixed, args.get(k))) {
                            matches = false;
                        }
                    }
                }
                if (!matches) {
                    break;
                }
            }
            if (matches) {
                return spec.body();
            }
        }
        return tpl.body();
    }

    // ------------------------------------------------------------------
    // Constructions
    // ------------------------------------------------------------------

    private Value evalRecordLit(final Expr.RecordLit lit, final Env env) {
        final ElabTables.Constructed made = tables.ctorOf.get(lit);
        final Map<String, Value> byName = new HashMap<>(lit.fields().size() * 2);
        for (final Expr.FieldInit f : lit.fields()) {
            byName.put(f.name(), eval(f.value(), env));
        }
        return switch (made) {
            case ElabTables.RecC r -> {
                final List<Value> ordered = new ArrayList<>(r.type().fields().size());
                for (final Type.Field field : r.type().fields()) {
                    ordered.add(byName.get(field.name()));
                }
                yield new Value.RecordV(r.type(), List.copyOf(ordered));
            }
            case ElabTables.VariantC v -> {
                final List<Value> ordered = new ArrayList<>(v.ctor().fields().size());
                for (final Type.Field field : v.ctor().fields()) {
                    ordered.add(byName.get(field.name()));
                }
                yield new Value.VariantV(v.sum(), v.ctor().name(), List.copyOf(ordered));
            }
            case ElabTables.StepC s -> new Value.StepV(s.type().event(), byName.get("guard"));
            case ElabTables.RuleC r -> {
                final Value triggerV = byName.get("trigger");
                if (!(triggerV instanceof Value.VariantV trigger)) {
                    error("V001", lit.pos(), "the trigger field did not reduce to a trigger value");
                    throw Abort.INSTANCE;
                }
                validateTrigger(trigger, r.type().events(), lit.pos());
                yield new Value.RuleV(null, r.type().events(), trigger,
                        byName.get("guard"), byName.get("verdict"));
            }
        };
    }

    private Value evalWith(final Expr.With w, final Env env) {
        final Value.RecordV base = (Value.RecordV) eval(w.base(), env);
        final List<Value> fields = new ArrayList<>(base.fields());
        for (final Expr.FieldInit f : w.fields()) {
            fields.set(base.type().fieldIndex(f.name()), eval(f.value(), env));
        }
        return new Value.RecordV(base.type(), List.copyOf(fields));
    }

    // ------------------------------------------------------------------
    // Match and operators
    // ------------------------------------------------------------------

    private Value evalMatch(final Expr.Match m, final Env env) {
        final Value subject = eval(m.subject(), env);
        for (final Expr.Arm arm : m.arms()) {
            if (matches(arm.pattern(), subject)) {
                return eval(arm.body(), env);
            }
        }
        // Exhaustiveness is proven at elaboration; reaching this point is an
        // internal inconsistency, reported rather than thrown raw.
        error("V013", m.pos(), "no match arm accepted the subject value");
        throw Abort.INSTANCE;
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

    private Value evalBinary(final Expr.Binary b, final Env env) {
        switch (b.op()) {
            case AND -> {
                final Value l = eval(b.left(), env);
                if (!((Value.BoolV) l).value()) {
                    return new Value.BoolV(false);
                }
                return eval(b.right(), env);
            }
            case OR -> {
                final Value l = eval(b.left(), env);
                if (((Value.BoolV) l).value()) {
                    return new Value.BoolV(true);
                }
                return eval(b.right(), env);
            }
            default -> {
                // Non-short-circuit operators evaluate both operands.
            }
        }
        final Value l = eval(b.left(), env);
        final Value r = eval(b.right(), env);
        return switch (b.op()) {
            case EQ -> new Value.BoolV(Value.equal(l, r));
            case NE -> new Value.BoolV(!Value.equal(l, r));
            case MATCHES -> {
                final String text = ((Value.TextV) l).value();
                final String pattern = ((Value.TextV) r).value();
                try {
                    yield new Value.BoolV(java.util.regex.Pattern
                            .compile(pattern).matcher(text).find());
                } catch (final PatternSyntaxException ex) {
                    error("V015", b.pos(), "invalid text pattern: " + ex.getDescription());
                    throw Abort.INSTANCE;
                }
            }
            case LT, GT, LE, GE -> new Value.BoolV(compare(l, r, b.op(), b.pos()));
            case ADD -> arith(l, r, b.pos(), '+');
            case SUB -> arith(l, r, b.pos(), '-');
            case MUL -> multiply(l, r, b.pos());
            case DIV -> divide(l, r, b.pos());
            case AND, OR -> throw Abort.INSTANCE; // Unreachable.
        };
    }

    private boolean compare(final Value l, final Value r, final org.synergyst.minutiae.lang.ast.BinOp op,
                            final Pos pos) {
        final int c;
        if (l instanceof Value.IntV a && r instanceof Value.IntV b) {
            c = Long.compare(a.value(), b.value());
        } else if (l instanceof Value.RealV a && r instanceof Value.RealV b) {
            c = Double.compare(a.value(), b.value());
        } else if (l instanceof Value.DurV a && r instanceof Value.DurV b) {
            c = Long.compare(a.value().millis(), b.value().millis());
        } else {
            error("V013", pos, "incomparable compile-time values");
            throw Abort.INSTANCE;
        }
        return switch (op) {
            case LT -> c < 0;
            case GT -> c > 0;
            case LE -> c <= 0;
            case GE -> c >= 0;
            default -> false;
        };
    }

    private Value arith(final Value l, final Value r, final Pos pos, final char op) {
        if (l instanceof Value.IntV a && r instanceof Value.IntV b) {
            try {
                yieldNothing();
                return new Value.IntV(op == '+'
                        ? Math.addExact(a.value(), b.value())
                        : Math.subtractExact(a.value(), b.value()));
            } catch (final ArithmeticException ex) {
                error("V006", pos, "integer overflow");
                throw Abort.INSTANCE;
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
                error("V008", pos, "duration arithmetic produced a negative span");
                throw Abort.INSTANCE;
            }
            return new Value.DurV(new DurationSpec(ms, false));
        }
        error("V013", pos, "invalid arithmetic operands");
        throw Abort.INSTANCE;
    }

    private Value multiply(final Value l, final Value r, final Pos pos) {
        if (l instanceof Value.IntV a && r instanceof Value.IntV b) {
            try {
                return new Value.IntV(Math.multiplyExact(a.value(), b.value()));
            } catch (final ArithmeticException ex) {
                error("V006", pos, "integer overflow");
                throw Abort.INSTANCE;
            }
        }
        if (l instanceof Value.RealV a && r instanceof Value.RealV b) {
            return new Value.RealV(a.value() * b.value());
        }
        final Value.DurV dur;
        final Value.IntV factor;
        if (l instanceof Value.DurV d && r instanceof Value.IntV f) {
            dur = d;
            factor = f;
        } else if (l instanceof Value.IntV f && r instanceof Value.DurV d) {
            dur = d;
            factor = f;
        } else {
            error("V013", pos, "invalid multiplication operands");
            throw Abort.INSTANCE;
        }
        if (factor.value() < 0L) {
            error("V008", pos, "a duration scale factor must be non-negative");
            throw Abort.INSTANCE;
        }
        try {
            return new Value.DurV(dur.value().times(factor.value()));
        } catch (final ArithmeticException ex) {
            error("V006", pos, "duration overflow");
            throw Abort.INSTANCE;
        }
    }

    private Value divide(final Value l, final Value r, final Pos pos) {
        if (l instanceof Value.IntV a && r instanceof Value.IntV b) {
            if (b.value() == 0L) {
                error("V005", pos, "division by zero");
                throw Abort.INSTANCE;
            }
            return new Value.IntV(a.value() / b.value());
        }
        if (l instanceof Value.RealV a && r instanceof Value.RealV b) {
            return new Value.RealV(a.value() / b.value());
        }
        error("V013", pos, "invalid division operands");
        throw Abort.INSTANCE;
    }

    private Value evalUnary(final Expr.Unary u, final Env env) {
        final Value v = eval(u.operand(), env);
        return switch (u.op()) {
            case NOT -> new Value.BoolV(!((Value.BoolV) v).value());
            case NEG -> switch (v) {
                case Value.IntV x -> {
                    try {
                        yield new Value.IntV(Math.negateExact(x.value()));
                    } catch (final ArithmeticException ex) {
                        error("V006", u.pos(), "integer overflow");
                        throw Abort.INSTANCE;
                    }
                }
                case Value.RealV x -> new Value.RealV(-x.value());
                default -> {
                    error("V013", u.pos(), "invalid negation operand");
                    throw Abort.INSTANCE;
                }
            };
        };
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    /**
     * Evaluates an elaborated compilation unit.
     *
     * @param elab        the elaboration result
     * @param diagnostics the sink receiving evaluation diagnostics
     * @return the residual artifacts
     */
    public static Output run(final ElabResult elab, final DiagnosticSink diagnostics) {
        final Evaluator ev = new Evaluator(elab, diagnostics);
        for (final Decl d : elab.declarations()) {
            try {
                ev.evalDecl(d);
            } catch (final Abort a) {
                // Reported; siblings continue.
            }
        }
        return new Output(Map.copyOf(ev.constants), List.copyOf(ev.layouts),
                ev.rules, List.copyOf(ev.expandedRules), ev.automata);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Symbol requireSymbol(final String name) {
        // Declarations reaching evaluation were accepted by elaboration and
        // therefore carry their symbols; absence is an internal inconsistency.
        final Symbol s = symbols.get(name);
        if (s == null) {
            throw Abort.INSTANCE;
        }
        return s;
    }

    private void error(final String code, final Pos pos, final String message) {
        diags.error(code, pos.line(), pos.column(), message);
    }

    private static void yieldNothing() {
        // Keeps the arithmetic try-blocks structurally uniform.
    }
}