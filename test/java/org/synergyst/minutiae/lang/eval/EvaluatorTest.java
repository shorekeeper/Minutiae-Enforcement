package org.synergyst.minutiae.lang.eval;

import org.junit.jupiter.api.Test;
import org.synergyst.minutiae.lang.CompiledUnit;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.synergyst.minutiae.lang.TestHarness.*;

/**
 * Compile-time normalisation: arithmetic, templates, matrices, transforms,
 * expansion, automaton set algebra, and trigger validation.
 */
final class EvaluatorTest {

    @Test
    void constantArithmeticFolds() {
        final CompiledUnit unit = compile("const Duration d = 1h * 4 + 30m;");
        assertTrue(unit.ok());
        final Value.DurV d = (Value.DurV) unit.constants().get("d");
        assertEquals(16_200_000L, d.value().millis());
    }

    @Test
    void divisionByZeroIsRejected() {
        final CompiledUnit unit = compile("const Int x = 1 / 0;");
        assertFalse(unit.ok());
        assertTrue(codes(unit.diagnostics()).contains("V005"));
    }

    @Test
    void integerOverflowIsRejected() {
        final CompiledUnit unit = compile(
                "const Int x = 9223372036854775807 + 1;");
        assertFalse(unit.ok());
        assertTrue(codes(unit.diagnostics()).contains("V006"));
    }

    @Test
    void templateConstraintGatesInstantiation() {
        final CompiledUnit unit = compile("""
                template<Measure M, Duration D>
                where { D <= 30d; }
                verdict ladder = (e) -> %s;
                rule<Chat> r {
                    condition {
                        trigger = Trigger::Atomic {};
                        guard = Guard::Always;
                    }
                    => verdict = ladder<Measure::MUTE, 40d>;
                }
                """.formatted(sanction("M", "Duration::Fixed(D)")));
        assertFalse(unit.ok());
        assertTrue(codes(unit.diagnostics()).contains("V003"));
    }

    @Test
    void matrixTransformExpandStampsLayouts() {
        final CompiledUnit unit = compile("""
                schema Row { Text key; Duration base; }
                matrix<Row> rows {
                    row("a", 1h);
                    row("b", 2h);
                }
                transform<Row -> Layout> stamp = (r) -> %s;
                expand stamp(rows);
                """.formatted(layout("r.key", "Measure::MUTE",
                "Duration::Fixed(r.base)", "Escalation::None", "{}")));
        assertTrue(unit.ok(), () -> unit.diagnostics().toString());
        assertEquals(2, unit.layouts().size());
        assertEquals("a", ((Value.TextV) unit.layouts().get(0).field("key")).value());
    }

    @Test
    void transformCompositionAppliesRightToLeft() {
        final CompiledUnit unit = compile("""
                schema Row { Text key; Duration base; }
                matrix<Row> rows {
                    row("a", 1h);
                }
                transform<Row -> Row> harden = (r) -> r with { .base = r.base * 2 };
                transform<Row -> Layout> stamp = (r) -> %s;
                transform<Row -> Layout> strict = stamp . harden;
                expand strict(rows);
                """.formatted(layout("r.key", "Measure::MUTE",
                "Duration::Fixed(r.base)", "Escalation::None", "{}")));
        assertTrue(unit.ok(), () -> unit.diagnostics().toString());
        final Value.VariantV duration =
                (Value.VariantV) unit.layouts().get(0).field("duration");
        assertEquals(7_200_000L, ((Value.DurV) duration.field(0)).value().millis());
    }

    @Test
    void duplicateLayoutKeyIsRejected() {
        final CompiledUnit unit = compile("""
                schema Row { Text key; }
                matrix<Row> rows {
                    row("a");
                    row("a");
                }
                transform<Row -> Layout> stamp = (r) -> %s;
                expand stamp(rows);
                """.formatted(layout("r.key", "Measure::KICK",
                "Duration::Permanent", "Escalation::None", "{}")));
        assertFalse(unit.ok());
        assertTrue(codes(unit.diagnostics()).contains("V016"));
    }

    @Test
    void overrideReplacesInheritedRulesOnEvent() {
        final CompiledUnit unit = compile("""
                rule<Chat> soft {
                    condition {
                        trigger = Trigger::Atomic {};
                        guard = Guard::Always;
                    }
                    => verdict = (e) -> %s;
                }
                rule<Chat> hard {
                    condition {
                        trigger = Trigger::Atomic {};
                        guard = Guard::Always;
                    }
                    => verdict = (e) -> %s;
                }
                automaton base {
                    use soft;
                }
                automaton strict : base {
                    override<Chat> use hard;
                }
                """.formatted(sanction("Measure::MUTE", "Duration::Fixed(1h)"),
                sanction("Measure::SUSPENSION", "Duration::Fixed(1d)")));
        assertTrue(unit.ok(), () -> unit.diagnostics().toString());
        final List<Value.RuleV> strict = unit.automata().get("strict");
        assertEquals(1, strict.size());
        assertEquals("hard", strict.get(0).name());
        assertEquals(1, unit.automata().get("base").size());
    }

    @Test
    void vacuousOverrideWarnsWithoutRejecting() {
        final CompiledUnit unit = compile("""
                rule<Chat> only {
                    condition {
                        trigger = Trigger::Atomic {};
                        guard = Guard::Always;
                    }
                    => verdict = (e) -> %s;
                }
                automaton root {
                    override<Chat> use only;
                }
                """.formatted(sanction("Measure::MUTE", "Duration::Fixed(1h)")));
        assertTrue(unit.ok());
        assertTrue(codes(unit.diagnostics()).contains("W001"));
    }

    @Test
    void repeatedTriggerBoundsAreValidated() {
        final CompiledUnit unit = compile("""
                rule<Chat> r {
                    condition {
                        trigger = Trigger::Repeated {
                            .count = 0, .within = 30s, .group_by = GroupBy::Subject,
                        };
                        guard = Guard::Always;
                    }
                    => verdict = (e) -> %s;
                }
                """.formatted(sanction("Measure::MUTE", "Duration::Fixed(1h)")));
        assertFalse(unit.ok());
        assertTrue(codes(unit.diagnostics()).contains("V001"));
    }

    @Test
    void sequenceStepsMustMatchDeclaredEvents() {
        final CompiledUnit unit = compile("""
                rule<Chat, Break> r {
                    condition {
                        trigger = Trigger::Sequence {
                            .within = 30s, .group_by = GroupBy::Subject,
                            .steps = {
                                Step<Chat> { .guard = (c) -> true },
                                Step<Chat> { .guard = (c) -> true },
                            },
                        };
                        guard = (c, b) -> true;
                    }
                    => verdict = (c, b) -> Sanction {
                        .target = c.subject, .cite = {},
                        .measure = Measure::MUTE,
                        .duration = Duration::Fixed(1h),
                        .escalation = Escalation::None,
                        .annotations = {},
                        .attribution = Attribution::System,
                        .dry_run = DryRun::InheritSafety,
                    };
                }
                """);
        assertFalse(unit.ok());
        assertTrue(codes(unit.diagnostics()).contains("V002"));
    }
}