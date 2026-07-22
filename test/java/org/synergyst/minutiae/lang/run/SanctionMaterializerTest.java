package org.synergyst.minutiae.lang.run;

import org.junit.jupiter.api.Test;
import org.synergyst.minutiae.annotation.RawAnnotation;
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
 * Translation of sanction descriptors onto the command surface consumed by
 * the shared resolver.
 */
final class SanctionMaterializerTest {

    private static final UUID SUBJECT = UUID.randomUUID();

    private static Value.RecordV descriptor() {
        final Planner.Result r = plan("""
                rule<Chat> r {
                    condition {
                        trigger = Trigger::Atomic {};
                        guard = Guard::Always;
                    }
                    => verdict = (e) -> %s;
                }
                automaton a { use r; }
                """.formatted(sanction("Measure::MUTE", "Duration::Fixed(3h)")),
                RULES);
        assertTrue(r.ok(), () -> r.diagnostics().toString());
        final UnitPlan unit = r.plan();
        final RulePlan p = unit.automata().get("a").get(0);
        final Value arg = EventAdapter.toRecord(facts(), p.eventShapes()[0]);
        return unit.interp().applyVerdict(p.verdict(), List.of(arg),
                new TestHarness.FakeEnv());
    }

    private static EventFacts facts() {
        return new EventFacts(EventKind.CHAT, SUBJECT, "Steve",
                Map.of("message", "x", "length", 1L));
    }

    @Test
    void descriptorMapsToCommandSurface() {
        final SanctionMaterializer.Materialized m =
                SanctionMaterializer.materialize(descriptor(), facts());

        assertEquals("Steve", m.command().target());
        assertNull(m.command().layoutKey());
        assertEquals("3h", m.command().overrides().get("duration"));
        assertTrue(m.systemAttribution());
        assertFalse(m.forcedDryRun());

        assertTrue(has(m.command().annotations(), "count", "P.2.1"));
        assertTrue(has(m.command().annotations(), "measure", "MUTE"));
        assertTrue(has(m.command().annotations(), "notify", "staff"));
    }

    @Test
    void foreignTargetFallsBackToUuidToken() {
        final EventFacts other = new EventFacts(EventKind.CHAT,
                UUID.randomUUID(), "Alex", Map.of("message", "x", "length", 1L));
        final SanctionMaterializer.Materialized m =
                SanctionMaterializer.materialize(descriptor(), other);
        assertEquals(SUBJECT.toString(), m.command().target());
    }

    private static boolean has(final List<RawAnnotation> annotations,
                               final String name, final String positional) {
        for (final RawAnnotation a : annotations) {
            if (a.name().equals(name) && a.positional().contains(positional)) {
                return true;
            }
        }
        return false;
    }
}