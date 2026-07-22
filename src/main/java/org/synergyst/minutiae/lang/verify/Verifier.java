package org.synergyst.minutiae.lang.verify;

import org.synergyst.minutiae.lang.ast.Expr;
import org.synergyst.minutiae.lang.diag.DiagnosticSink;
import org.synergyst.minutiae.lang.eval.Evaluator;
import org.synergyst.minutiae.lang.eval.Value;
import org.synergyst.minutiae.measure.Measure;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Post-normalisation verifier of residual artifacts.
 *
 * <p>Verification runs after compile-time evaluation and before lowering, and
 * establishes every invariant the dispatch engine and the layout registry
 * rely upon but cannot see in the residual values' types alone. Verification
 * never mutates its input; each violation is reported as a positioned
 * diagnostic against the unit origin, since residual values carry no source
 * positions of their own.
 *
 * <h2>Layout invariants</h2>
 * <ul>
 *   <li>{@code R001} - the cited rule identifier must exist in the rule
 *       registry.</li>
 *   <li>{@code R002} - the measure-duration temporal invariant: a temporal
 *       measure requires {@code Duration::Fixed} with a positive span; the
 *       permanent measure requires {@code Duration::Permanent}; an
 *       instantaneous measure requires {@code Duration::Permanent}, which
 *       lowers to the absence of a span.</li>
 *   <li>{@code R003} - an {@code Escalation::Steps} ladder must be non-empty,
 *       every rung must be positive, and only the final rung may be the
 *       permanent term.</li>
 *   <li>{@code R004} - the layout key must be non-blank.</li>
 *   <li>{@code R005} - layout annotations must be configuration-scoped;
 *       {@code Reason}, {@code Link}, and {@code Stay} are command-scoped and
 *       are rejected.</li>
 * </ul>
 *
 * <h2>Rule invariants</h2>
 * <ul>
 *   <li>{@code R001} - every rule-identifier literal syntactically reachable
 *       from a guard body, a verdict body, or a sequence step guard body must
 *       exist in the rule registry. Reachability is computed by walking the
 *       closure bodies' abstract syntax; identifiers produced by template
 *       parameters are covered at their instantiation sites, whose arguments
 *       are literals or constants themselves walked here.</li>
 *   <li>{@code W003} - a declared rule that is a member of no automaton is
 *       reported as unused; it is compiled and verified but never armed.</li>
 * </ul>
 */
public final class Verifier {

    /** Annotation constructors admissible in layout (configuration) scope. */
    private static final Set<String> LAYOUT_SCOPE = Set.of(
            "Notify", "Evidence", "Escalate", "Silent", "Shadow", "Ghost",
            "Rubberband", "Transcript", "WarnFirst", "Decay", "Tariff", "Probation");

    private final DiagnosticSink diags;
    private final Predicate<String> ruleExists;
    private final String origin;

    private Verifier(final DiagnosticSink diags, final Predicate<String> ruleExists,
                     final String origin) {
        this.diags = diags;
        this.ruleExists = ruleExists;
        this.origin = origin;
    }

    /**
     * Verifies the residual artifacts of one unit.
     *
     * @param out        the evaluator output
     * @param ruleExists membership predicate over the rule registry
     * @param origin     the unit origin, used in diagnostics
     * @param diags      the sink receiving verification diagnostics
     */
    public static void verify(final Evaluator.Output out, final Predicate<String> ruleExists,
                              final String origin, final DiagnosticSink diags) {
        final Verifier v = new Verifier(diags, ruleExists, origin);
        for (final Value.RecordV layout : out.layouts()) {
            v.verifyLayout(layout);
        }
        for (final Value.RuleV rule : out.rules().values()) {
            v.verifyRule(rule);
        }
        for (final Value.RuleV rule : out.expandedRules()) {
            v.verifyRule(rule);
        }
        v.reportUnused(out);
    }

    // ------------------------------------------------------------------
    // Layouts
    // ------------------------------------------------------------------

    private void verifyLayout(final Value.RecordV layout) {
        final String key = ((Value.TextV) layout.field("key")).value();
        if (key.isBlank()) {
            error("R004", "layout key is blank");
            return;
        }
        final String rule = ((Value.RuleIdV) layout.field("rule")).id();
        if (!ruleExists.test(rule)) {
            error("R001", "layout '" + key + "' cites unknown rule '" + rule + "'");
        }
        verifyTemporal(key, (Value.VariantV) layout.field("measure"),
                (Value.VariantV) layout.field("duration"));
        verifyLadder(key, (Value.VariantV) layout.field("escalation"));
        for (final Value a : ((Value.ListV) layout.field("annotations")).items()) {
            final String ctor = ((Value.VariantV) a).ctor();
            if (!LAYOUT_SCOPE.contains(ctor)) {
                error("R005", "layout '" + key + "' carries command-scoped annotation '"
                        + ctor + "'");
            }
        }
    }

    private void verifyTemporal(final String key, final Value.VariantV measure,
                                final Value.VariantV duration) {
        final Measure m = Measure.parse(measure.ctor());
        switch (m.temporal()) {
            case TEMPORAL -> {
                if (!duration.ctor().equals("Fixed")) {
                    error("R002", "layout '" + key + "': temporal measure " + m
                            + " requires Duration::Fixed");
                    return;
                }
                if (((Value.DurV) duration.field(0)).value().millis() <= 0L) {
                    error("R002", "layout '" + key + "': duration must be positive");
                }
            }
            case PERMANENT, INSTANTANEOUS -> {
                if (!duration.ctor().equals("Permanent")) {
                    error("R002", "layout '" + key + "': measure " + m
                            + " requires Duration::Permanent");
                }
            }
        }
    }

    private void verifyLadder(final String key, final Value.VariantV escalation) {
        if (!escalation.ctor().equals("Steps")) {
            return;
        }
        final List<Value> steps = ((Value.ListV) escalation.field(0)).items();
        if (steps.isEmpty()) {
            error("R003", "layout '" + key + "': escalation ladder is empty");
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            final Value.VariantV rung = (Value.VariantV) steps.get(i);
            if (rung.ctor().equals("Permanent")) {
                if (i != steps.size() - 1) {
                    error("R003", "layout '" + key
                            + "': only the final rung may be permanent");
                }
            } else if (((Value.DurV) rung.field(0)).value().millis() <= 0L) {
                error("R003", "layout '" + key + "': rung " + (i + 1)
                        + " must be positive");
            }
        }
    }

    // ------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------

    private void verifyRule(final Value.RuleV rule) {
        walkValue(rule.guard(), rule.name());
        walkValue(rule.verdict(), rule.name());
        if (rule.trigger().ctor().equals("Sequence")) {
            for (final Value step : ((Value.ListV) rule.trigger().field(2)).items()) {
                walkValue(((Value.StepV) step).guard(), rule.name());
            }
        }
    }

    /** Walks closure bodies reachable from a value, checking rule literals. */
    private void walkValue(final Value v, final String ruleName) {
        switch (v) {
            case Value.ClosureV cl -> walkExpr(cl.body(), ruleName);
            case Value.ComposeV co -> {
                walkValue(co.outer(), ruleName);
                walkValue(co.inner(), ruleName);
            }
            default -> {
                // Non-function values carry no syntax to inspect.
            }
        }
    }

    private void walkExpr(final Expr e, final String ruleName) {
        switch (e) {
            case Expr.RuleLit lit -> {
                if (!ruleExists.test(lit.id())) {
                    error("R001", "rule '" + ruleName + "' cites unknown rule '"
                            + lit.id() + "' at " + lit.pos().line() + ":"
                            + lit.pos().column());
                }
            }
            case Expr.Member m -> walkExpr(m.receiver(), ruleName);
            case Expr.Call c -> {
                walkExpr(c.callee(), ruleName);
                for (final Expr a : c.args()) {
                    walkExpr(a, ruleName);
                }
            }
            case Expr.Instantiate i -> {
                for (final Expr a : i.args()) {
                    walkExpr(a, ruleName);
                }
            }
            case Expr.RecordLit lit -> {
                for (final Expr.FieldInit f : lit.fields()) {
                    walkExpr(f.value(), ruleName);
                }
            }
            case Expr.ListLit list -> {
                for (final Expr item : list.items()) {
                    walkExpr(item, ruleName);
                }
            }
            case Expr.Lambda lam -> walkExpr(lam.body(), ruleName);
            case Expr.Match m -> {
                walkExpr(m.subject(), ruleName);
                for (final Expr.Arm arm : m.arms()) {
                    walkExpr(arm.body(), ruleName);
                }
            }
            case Expr.Binary b -> {
                walkExpr(b.left(), ruleName);
                walkExpr(b.right(), ruleName);
            }
            case Expr.Unary u -> walkExpr(u.operand(), ruleName);
            case Expr.With w -> {
                walkExpr(w.base(), ruleName);
                for (final Expr.FieldInit f : w.fields()) {
                    walkExpr(f.value(), ruleName);
                }
            }
            default -> {
                // Remaining literals and references carry no rule identifiers.
            }
        }
    }

    private void reportUnused(final Evaluator.Output out) {
        final Set<String> armed = new HashSet<>();
        for (final List<Value.RuleV> members : out.automata().values()) {
            for (final Value.RuleV r : members) {
                armed.add(r.name());
            }
        }
        for (final String declared : out.rules().keySet()) {
            if (!armed.contains(declared)) {
                diags.warning("W003", 0, 0, "rule '" + declared
                        + "' in " + origin + " is a member of no automaton and is never armed");
            }
        }
    }

    private void error(final String code, final String message) {
        diags.error(code, 0, 0, origin + ": " + message);
    }
}