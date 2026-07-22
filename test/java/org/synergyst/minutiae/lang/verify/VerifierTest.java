package org.synergyst.minutiae.lang.verify;

import org.junit.jupiter.api.Test;
import org.synergyst.minutiae.lang.plan.Planner;

import static org.junit.jupiter.api.Assertions.*;
import static org.synergyst.minutiae.lang.TestHarness.*;

/**
 * Post-normalisation invariants: rule-registry citations, the
 * measure-duration temporal invariant, ladder shape, annotation scope, and
 * unused-rule reporting.
 */
final class VerifierTest {

    private static String stamped(final String layoutLiteral) {
        return """
                schema Row { Text key; }
                matrix<Row> rows { row("k"); }
                transform<Row -> Layout> stamp = (r) -> %s;
                expand stamp(rows);
                """.formatted(layoutLiteral);
    }

    @Test
    void unknownCitedRuleIsRejected() {
        final Planner.Result r = plan(
                stamped(layout("r.key", "Measure::MUTE", "Duration::Fixed(1h)",
                        "Escalation::None", "{}")),
                id -> false);
        assertFalse(r.ok());
        assertTrue(codes(r.diagnostics()).contains("R001"));
    }

    @Test
    void temporalMeasureRequiresFixedDuration() {
        final Planner.Result r = plan(
                stamped(layout("r.key", "Measure::MUTE", "Duration::Permanent",
                        "Escalation::None", "{}")),
                RULES);
        assertFalse(r.ok());
        assertTrue(codes(r.diagnostics()).contains("R002"));
    }

    @Test
    void instantaneousMeasureRejectsFixedDuration() {
        final Planner.Result r = plan(
                stamped(layout("r.key", "Measure::KICK", "Duration::Fixed(1h)",
                        "Escalation::None", "{}")),
                RULES);
        assertFalse(r.ok());
        assertTrue(codes(r.diagnostics()).contains("R002"));
    }

    @Test
    void permanentRungMustBeFinal() {
        final Planner.Result r = plan(
                stamped(layout("r.key", "Measure::MUTE", "Duration::Fixed(1h)",
                        "Escalation::Steps({ Duration::Permanent, Duration::Fixed(1h) })",
                        "{}")),
                RULES);
        assertFalse(r.ok());
        assertTrue(codes(r.diagnostics()).contains("R003"));
    }

    @Test
    void commandScopedAnnotationIsRejectedInLayouts() {
        final Planner.Result r = plan(
                stamped(layout("r.key", "Measure::MUTE", "Duration::Fixed(1h)",
                        "Escalation::None", "{ Annotation::Reason(\"x\") }")),
                RULES);
        assertFalse(r.ok());
        assertTrue(codes(r.diagnostics()).contains("R005"));
    }

    @Test
    void guardCitationsAreVerified() {
        final Planner.Result r = plan("""
                rule<Chat> r {
                    condition {
                        trigger = Trigger::Atomic {};
                        guard = (e) -> precedent(e.subject, P.9.9) >= 0;
                    }
                    => verdict = (e) -> %s;
                }
                automaton a { use r; }
                """.formatted(sanction("Measure::MUTE", "Duration::Fixed(1h)")),
                RULES);
        assertFalse(r.ok());
        assertTrue(codes(r.diagnostics()).contains("R001"));
    }

    @Test
    void ruleOutsideAnyAutomatonWarnsButPlans() {
        final Planner.Result r = plan("""
                rule<Chat> orphan {
                    condition {
                        trigger = Trigger::Atomic {};
                        guard = Guard::Always;
                    }
                    => verdict = (e) -> %s;
                }
                """.formatted(sanction("Measure::MUTE", "Duration::Fixed(1h)")),
                RULES);
        assertTrue(r.ok());
        assertTrue(codes(r.diagnostics()).contains("W003"));
    }
}