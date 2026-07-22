package org.synergyst.minutiae.lang.run;

import org.junit.jupiter.api.Test;
import org.synergyst.minutiae.engine.EventFacts;
import org.synergyst.minutiae.engine.EventKind;
import org.synergyst.minutiae.lang.TestHarness;
import org.synergyst.minutiae.lang.eval.Value;
import org.synergyst.minutiae.lang.plan.Planner;
import org.synergyst.minutiae.lang.plan.RulePlan;
import org.synergyst.minutiae.lang.plan.UnitPlan;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.synergyst.minutiae.lang.TestHarness.*;

/**
 * Runtime evaluation of guard and verdict closures: environment built-ins,
 * on-demand template instantiation with specialisation selection, and
 * fail-safe absorption of dynamic faults.
 */
final class InterpTest {

    private static final UUID SUBJECT = UUID.randomUUID();

    private static final String UNIT = """
            template<Measure M, Duration D>
            where { D <= 30d; }
            verdict ladder = (e) -> %s;
            template<> verdict ladder<Measure::CUSTODY, _> = (e) -> %s;

            rule<Chat> flood {
                condition {
                    trigger = Trigger::Atomic {};
                    guard = (e) -> e.length > 120 && precedent(e.subject, P.2.1) >= 1;
                }
                => verdict = ladder<Measure::MUTE, 3h>;
            }
            rule<Chat> graded {
                condition {
                    trigger = Trigger::Atomic {};
                    guard = Guard::Always;
                }
                => verdict = (e) -> match (e.length) {
                    0..100 => ladder<Measure::MUTE, 1h>(e),
                    _      => ladder<Measure::CUSTODY, 1h>(e),
                };
            }
            rule<Chat> fragile {
                condition {
                    trigger = Trigger::Atomic {};
                    guard = Guard::Always;
                }
                => verdict = (e) -> ladder<Measure::MUTE, 40d>(e);
            }
            automaton a { use flood; use graded; use fragile; }
            """.formatted(sanction("M", "Duration::Fixed(D)"),
            sanction("Measure::CUSTODY", "Duration::Permanent"));

    private static UnitPlan planUnit() {
        final Planner.Result r = plan(UNIT, RULES);
        assertTrue(r.ok(), () -> r.diagnostics().toString());
        return r.plan();
    }

    private static RulePlan named(final UnitPlan unit, final String name) {
        for (final RulePlan p : unit.automata().get("a")) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        throw new AssertionError(name);
    }

    private static Value chatArg(final RulePlan p, final long length) {
        return EventAdapter.toRecord(new EventFacts(EventKind.CHAT, SUBJECT, "Steve",
                Map.of("message", "hello", "length", length)), p.eventShapes()[0]);
    }

    @Test
    void guardConsultsTheLiveEnvironment() {
        final UnitPlan unit = planUnit();
        final RulePlan flood = named(unit, "flood");
        final TestHarness.FakeEnv env = new TestHarness.FakeEnv();

        env.precedent.put(SUBJECT + "|P.2.1", 1L);
        assertTrue(unit.interp().applyGuard(flood.guard(),
                List.of(chatArg(flood, 200)), env));

        env.precedent.put(SUBJECT + "|P.2.1", 0L);
        assertFalse(unit.interp().applyGuard(flood.guard(),
                List.of(chatArg(flood, 200)), env));

        env.precedent.put(SUBJECT + "|P.2.1", 1L);
        assertFalse(unit.interp().applyGuard(flood.guard(),
                List.of(chatArg(flood, 50)), env));
    }

    @Test
    void verdictYieldsTotalDescriptor() {
        final UnitPlan unit = planUnit();
        final RulePlan flood = named(unit, "flood");

        final Value.RecordV s = unit.interp().applyVerdict(flood.verdict(),
                List.of(chatArg(flood, 200)), new TestHarness.FakeEnv());

        assertEquals("MUTE", ((Value.VariantV) s.field("measure")).ctor());
        final Value.VariantV duration = (Value.VariantV) s.field("duration");
        assertEquals("Fixed", duration.ctor());
        assertEquals(3L * 3600 * 1000, ((Value.DurV) duration.field(0)).value().millis());
        assertEquals(SUBJECT.toString(), ((Value.TextV) s.field("target")).value());
    }

    @Test
    void runtimeInstantiationSelectsSpecialisation() {
        final UnitPlan unit = planUnit();
        final RulePlan graded = named(unit, "graded");
        final TestHarness.FakeEnv env = new TestHarness.FakeEnv();

        final Value.RecordV low = unit.interp().applyVerdict(graded.verdict(),
                List.of(chatArg(graded, 50)), env);
        assertEquals("MUTE", ((Value.VariantV) low.field("measure")).ctor());

        final Value.RecordV high = unit.interp().applyVerdict(graded.verdict(),
                List.of(chatArg(graded, 500)), env);
        assertEquals("CUSTODY", ((Value.VariantV) high.field("measure")).ctor());
        assertEquals("Permanent",
                ((Value.VariantV) high.field("duration")).ctor());
    }

    @Test
    void runtimeConstraintViolationRaisesFailure() {
        final UnitPlan unit = planUnit();
        final RulePlan fragile = named(unit, "fragile");
        assertThrows(Interp.Failure.class, () -> unit.interp().applyVerdict(
                fragile.verdict(), List.of(chatArg(fragile, 10)),
                new TestHarness.FakeEnv()));
    }
}