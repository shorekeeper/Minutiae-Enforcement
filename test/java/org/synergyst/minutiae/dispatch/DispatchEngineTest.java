package org.synergyst.minutiae.dispatch;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.synergyst.minutiae.engine.EventFacts;
import org.synergyst.minutiae.engine.EventKind;
import org.synergyst.minutiae.engine.SafetyConfig;
import org.synergyst.minutiae.engine.SystemActor;
import org.synergyst.minutiae.lang.TestHarness;
import org.synergyst.minutiae.lang.plan.Planner;
import org.synergyst.minutiae.lang.plan.RulePlan;
import org.synergyst.minutiae.lang.plan.UnitPlan;
import org.synergyst.minutiae.log.KernelLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.synergyst.minutiae.lang.TestHarness.*;

/**
 * Gating behaviour of the dispatch engine observed through its public
 * surface: rule indexing, recurrence-window accrual, sequence completion,
 * throttle consumption, and self-muting.
 *
 * <p>The engine is constructed without a resolver or executor; a firing that
 * reaches resolution fails with an absorbed runtime fault, which is the
 * engine's documented posture toward any firing-path error. Throttle
 * consumption precedes resolution, so muting remains observable.
 */
final class DispatchEngineTest {

    private static final UUID SUBJECT = UUID.randomUUID();

    private static KernelLogger log() {
        return new KernelLogger(LoggerFactory.getLogger("dispatch-test"),
                System.nanoTime(), false);
    }

    private static SafetyConfig safety(final int throttlePerMin) {
        return new SafetyConfig(false, Set.of(), throttlePerMin,
                new SystemActor("SYSTEM", Set.of(), false));
    }

    private static List<ArmedRule> armed(final String src) {
        final Planner.Result r = plan(src, RULES);
        assertTrue(r.ok(), () -> r.diagnostics().toString());
        final UnitPlan unit = r.plan();
        final List<ArmedRule> out = new ArrayList<>();
        for (final Map.Entry<String, List<RulePlan>> e : unit.automata().entrySet()) {
            for (final RulePlan p : e.getValue()) {
                out.add(new ArmedRule(e.getKey(), p, unit.interp()));
            }
        }
        return out;
    }

    private static EventFacts chat(final String message) {
        return new EventFacts(EventKind.CHAT, SUBJECT, "Steve",
                Map.of("message", message, "length", (long) message.length()));
    }

    private static EventFacts brk() {
        return new EventFacts(EventKind.BREAK, SUBJECT, "Steve",
                Map.of("block", "CHEST"));
    }

    private static DispatchEngine engine(final String src, final int throttlePerMin,
                                         final TestHarness.FakeEnv env) {
        return new DispatchEngine(log(), armed(src), null, null, null, null,
                safety(throttlePerMin), env, () -> null);
    }

    private static final String ATOMIC = """
            rule<Chat> r {
                condition {
                    trigger = Trigger::Atomic {};
                    guard = (e) -> e.length > 3;
                }
                => verdict = (e) -> %s;
            }
            automaton auto { use r; }
            """.formatted(sanction("Measure::MUTE", "Duration::Fixed(1h)"));

    @Test
    void indexesRulesByEventKind() {
        final DispatchEngine engine = engine(ATOMIC, 30, new TestHarness.FakeEnv());
        assertEquals(1, engine.ruleCount());
        assertEquals(1, engine.rulesFor(EventKind.CHAT).size());
        assertTrue(engine.rulesFor(EventKind.BREAK).isEmpty());
    }

    @Test
    void failingGuardConsumesNoThrottle() {
        final DispatchEngine engine = engine(ATOMIC, 1, new TestHarness.FakeEnv());
        for (int i = 0; i < 5; i++) {
            engine.handle(chat("ab"));
        }
        assertFalse(engine.isMuted("auto"));
    }

    @Test
    void throttleSaturationSelfMutes() {
        final DispatchEngine engine = engine(ATOMIC, 1, new TestHarness.FakeEnv());
        engine.handle(chat("long enough"));
        assertFalse(engine.isMuted("auto"));
        engine.handle(chat("long enough"));
        assertTrue(engine.isMuted("auto"));
        // A muted automaton never evaluates again; no fault propagates.
        engine.handle(chat("long enough"));
    }

    @Test
    void recurrenceWindowAccruesBeforeFiring() {
        final String src = """
                rule<Chat> r {
                    condition {
                        trigger = Trigger::Repeated {
                            .count = 3, .within = 30s, .group_by = GroupBy::Subject,
                        };
                        guard = Guard::Always;
                    }
                    => verdict = (e) -> %s;
                }
                automaton auto { use r; }
                """.formatted(sanction("Measure::MUTE", "Duration::Fixed(1h)"));
        final TestHarness.FakeEnv env = new TestHarness.FakeEnv();
        final DispatchEngine engine = engine(src, 1, env);

        engine.handle(chat("a"));
        engine.handle(chat("b"));
        assertFalse(engine.isMuted("auto"));
        // The third occurrence fires and consumes the single throttle unit;
        // three further occurrences accrue a fresh window and fire again,
        // which saturates the throttle and mutes the automaton.
        engine.handle(chat("c"));
        engine.handle(chat("d"));
        engine.handle(chat("e"));
        engine.handle(chat("f"));
        assertTrue(engine.isMuted("auto"));
    }

    @Test
    void sequenceCompletionFires() {
        final String src = """
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
                automaton auto { use combo; }
                """;
        final DispatchEngine engine = engine(src, 1, new TestHarness.FakeEnv());

        // A break without a preceding taunt completes nothing.
        engine.handle(brk());
        assertFalse(engine.isMuted("auto"));

        // Taunt then break completes the sequence and consumes the throttle;
        // a second completion saturates it and mutes the automaton.
        engine.handle(chat("ez noob"));
        engine.handle(brk());
        assertFalse(engine.isMuted("auto"));
        engine.handle(chat("ez again"));
        engine.handle(brk());
        assertTrue(engine.isMuted("auto"));
    }
}