package org.synergyst.minutiae.lang.plan;

import org.synergyst.minutiae.lang.ast.Decl;
import org.synergyst.minutiae.lang.diag.Diagnostic;
import org.synergyst.minutiae.lang.diag.Diagnostics;
import org.synergyst.minutiae.lang.eval.Evaluator;
import org.synergyst.minutiae.lang.lex.Lexer;
import org.synergyst.minutiae.lang.lower.Lowering;
import org.synergyst.minutiae.lang.parse.Parser;
import org.synergyst.minutiae.lang.run.Interp;
import org.synergyst.minutiae.lang.sem.ElabResult;
import org.synergyst.minutiae.lang.sem.Elaborator;
import org.synergyst.minutiae.lang.verify.Verifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;

/**
 * End-to-end planner: compiles one source through every phase and yields the
 * verified, lowered {@link UnitPlan}.
 *
 * <p>Phase order is lexing, parsing, elaboration, compile-time evaluation,
 * verification, lowering. All phases share one diagnostic collection. The
 * gating policy is strict at two boundaries: evaluation runs only when the
 * syntactic and semantic phases produced no error, and a plan is published
 * only when evaluation and verification produced no error. A source
 * therefore either yields a complete plan whose every invariant holds, or no
 * plan at all - never a partially armed unit.
 *
 * <p>Planning is confined to the calling thread, performs no I/O, and holds
 * no state; the caller supplies source text and the rule-registry membership
 * predicate against which citations are verified.
 */
public final class Planner {

    /**
     * Outcome of planning one source.
     *
     * @param plan        the verified plan, or null when planning failed
     * @param diagnostics diagnostics of all phases, in report order
     * @param ok          whether a plan was produced
     */
    public record Result(UnitPlan plan, List<Diagnostic> diagnostics, boolean ok) {
    }

    private Planner() {
    }

    /**
     * Plans one source.
     *
     * @param origin     source identifier, typically a file name
     * @param text       source text
     * @param ruleExists membership predicate over the rule registry
     * @return the planning result
     */
    public static Result plan(final String origin, final String text,
                              final Predicate<String> ruleExists) {
        final Diagnostics diags = new Diagnostics();

        final var tokens = Lexer.lex(text, diags);
        final List<Decl> decls = Parser.parse(tokens, diags);
        final ElabResult elab = Elaborator.run(decls, diags);
        if (diags.hasErrors()) {
            return new Result(null, diags.all(), false);
        }

        final Evaluator.Output out = Evaluator.run(elab, diags);
        if (diags.hasErrors()) {
            return new Result(null, diags.all(), false);
        }

        Verifier.verify(out, ruleExists, origin, diags);
        if (diags.hasErrors()) {
            return new Result(null, diags.all(), false);
        }

        // The interpreter context binds the elaboration tables and the symbol
        // table; every closure of the plan is evaluated through it.
        final Interp interp = new Interp(elab.tables(), elab.symbols());
        final LinkedHashMap<String, List<RulePlan>> automata =
                Lowering.lowerAutomata(out, interp);
        final UnitPlan plan = new UnitPlan(origin,
                Lowering.lowerLayouts(out), automata, interp);
        return new Result(plan, diags.all(), true);
    }
}