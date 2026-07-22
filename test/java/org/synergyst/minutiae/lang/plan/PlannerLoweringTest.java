package org.synergyst.minutiae.lang.plan;

import org.junit.jupiter.api.Test;
import org.synergyst.minutiae.engine.EventKind;
import org.synergyst.minutiae.layout.LayoutDefinition;
import org.synergyst.minutiae.measure.Measure;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.synergyst.minutiae.lang.TestHarness.*;

/**
 * End-to-end planning and the lowering of triggers, event shapes, and layout
 * descriptors onto the runtime vocabulary.
 */
final class PlannerLoweringTest {

    @Test
    void repeatedTriggerLowersToPrimitiveData() {
        final Planner.Result r = plan("""
                rule<Chat> flood {
                    condition {
                        trigger = Trigger::Repeated {
                            .count = 5, .within = 30s, .group_by = GroupBy::Subject,
                        };
                        guard = (e) -> e.length > 120;
                    }
                    => verdict = (e) -> %s;
                }
                automaton watch { use flood; }
                """.formatted(sanction("Measure::MUTE", "Duration::Fixed(3h)")),
                RULES);
        assertTrue(r.ok(), () -> r.diagnostics().toString());

        final List<RulePlan> plans = r.plan().automata().get("watch");
        assertEquals(1, plans.size());
        final RulePlan p = plans.get(0);
        assertEquals(EventKind.CHAT, p.primaryKind());
        assertEquals("Chat", p.eventShapes()[0].name());

        final TriggerPlan.Repeated rep = (TriggerPlan.Repeated) p.trigger();
        assertEquals(5, rep.count());
        assertEquals(30_000L, rep.withinMs());
        assertEquals(TriggerPlan.Part.Kind.SUBJECT, rep.part().kind());
    }

    @Test
    void sequenceTriggerLowersStepKinds() {
        final Planner.Result r = plan("""
                rule<Chat, Break> combo {
                    condition {
                        trigger = Trigger::Sequence {
                            .within = 30s, .group_by = GroupBy::Subject,
                            .steps = {
                                Step<Chat> { .guard = (c) -> c.message ~ "ez" },
                                Step<Break> { .guard = (b) -> true },
                            },
                        };
                        guard = (c, b) -> true;
                    }
                    => verdict = (c, b) -> Sanction {
                        .target = c.subject, .cite = { P.3.2 },
                        .measure = Measure::SUSPENSION,
                        .duration = Duration::Fixed(1d),
                        .escalation = Escalation::None,
                        .annotations = {},
                        .attribution = Attribution::System,
                        .dry_run = DryRun::InheritSafety,
                    };
                }
                automaton watch { use combo; }
                """, RULES);
        assertTrue(r.ok(), () -> r.diagnostics().toString());

        final RulePlan p = r.plan().automata().get("watch").get(0);
        assertArrayEquals(new EventKind[]{EventKind.CHAT, EventKind.BREAK}, p.eventKinds());
        final TriggerPlan.Sequence seq = (TriggerPlan.Sequence) p.trigger();
        assertEquals(2, seq.steps().length);
        assertEquals(EventKind.CHAT, seq.steps()[0].kind());
        assertEquals(EventKind.BREAK, seq.steps()[1].kind());
    }

    @Test
    void layoutDescriptorLowersToRegistryDefinition() {
        final Planner.Result r = plan("""
                schema Row { Text key; Duration base; }
                matrix<Row> rows { row("grief", 7d); }
                transform<Row -> Layout> stamp = (r) -> Layout {
                    .key = r.key,
                    .rule = P.3.2,
                    .reason = "griefing",
                    .measure = Measure::SUSPENSION,
                    .duration = Duration::Fixed(r.base),
                    .escalation = Escalation::Steps({
                        Duration::Fixed(r.base),
                        Duration::Fixed(r.base * 4),
                        Duration::Permanent,
                    }),
                    .annotations = { Annotation::Evidence, Annotation::Escalate },
                };
                expand stamp(rows);
                """, RULES);
        assertTrue(r.ok(), () -> r.diagnostics().toString());

        final LayoutDefinition def = r.plan().layouts().get(0);
        assertEquals("grief", def.key());
        assertEquals("P.3.2", def.rule());
        assertEquals(Measure.SUSPENSION, def.measure());
        assertEquals(7L * 24 * 3600 * 1000, def.duration().millis());
        assertEquals(3, def.escalation().length);
        assertTrue(def.escalation()[2].permanent());
        assertEquals(28L * 24 * 3600 * 1000, def.escalation()[1].millis());
        assertEquals(2, def.annotations().size());
        assertEquals("evidence", def.annotations().get(0).name());
    }

    @Test
    void failedVerificationYieldsNoPlan() {
        final Planner.Result r = plan("""
                rule<Chat> r {
                    condition {
                        trigger = Trigger::Atomic {};
                        guard = Guard::Always;
                    }
                    => verdict = (e) -> %s;
                }
                automaton a { use r; }
                """.formatted(sanction("Measure::MUTE", "Duration::Fixed(1h)")),
                id -> false);
        assertFalse(r.ok());
        assertNull(r.plan());
    }
}