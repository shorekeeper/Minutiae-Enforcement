package org.synergyst.minutiae.dispatch;

import org.bukkit.command.CommandSender;
import org.synergyst.minutiae.annotation.RawAnnotation;
import org.synergyst.minutiae.command.parse.ParsedCommand;
import org.synergyst.minutiae.engine.EventFacts;
import org.synergyst.minutiae.engine.EventKind;
import org.synergyst.minutiae.engine.FireDecision;
import org.synergyst.minutiae.engine.GuardEnvironment;
import org.synergyst.minutiae.engine.SafetyConfig;
import org.synergyst.minutiae.engine.SequenceTracker;
import org.synergyst.minutiae.engine.Throttle;
import org.synergyst.minutiae.engine.WindowTracker;
import org.synergyst.minutiae.execute.SanctionExecutor;
import org.synergyst.minutiae.lang.eval.Value;
import org.synergyst.minutiae.lang.plan.RulePlan;
import org.synergyst.minutiae.lang.plan.TriggerPlan;
import org.synergyst.minutiae.lang.run.EventAdapter;
import org.synergyst.minutiae.lang.run.Interp;
import org.synergyst.minutiae.lang.run.SanctionMaterializer;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.message.Arg;
import org.synergyst.minutiae.message.MessageKey;
import org.synergyst.minutiae.message.MessageService;
import org.synergyst.minutiae.resolve.ResolveException;
import org.synergyst.minutiae.resolve.ResolvedSanction;
import org.synergyst.minutiae.resolve.SanctionResolver;
import org.synergyst.minutiae.storage.Storage;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Dispatch engine binding armed rule plans to platform events.
 *
 * <p>At construction the engine indexes every armed rule under each event
 * kind its trigger observes: an atomic or repeated rule under its single
 * event, a sequence rule under the kind of every step. On each incoming
 * event the engine gates every candidate and fires those that pass.
 *
 * <h2>Gating</h2>
 * <p>An automaton present in the self-mute set is rejected outright. An
 * atomic trigger requires only a passing guard. A repeated trigger requires a
 * passing guard on each occurrence and fires when the recurrence window
 * reaches its threshold within its span, accrued per partition. A sequence
 * trigger advances a per-partition partial match whose step admission is
 * decided by the plan's step-guard closures; only completion fires. A firing
 * that survives its trigger consumes one unit of the owning automaton's
 * per-minute throttle; saturating the throttle self-mutes the automaton,
 * bounding the blast radius of a misbehaving definition.
 *
 * <h2>Sequence fact capture</h2>
 * <p>A sequence rule's guard and verdict observe every participating event,
 * not only the completing one. The engine therefore captures the facts of
 * each admitted step per partition, synchronising the capture list with the
 * tracker by observing its step index around each advance: an advance
 * appends, a restart replaces, a completion drains the list and supplies the
 * full argument vector. Capture entries are reclaimed by an amortised sweep
 * that drops partitions whose tracker holds no partial match.
 *
 * <h2>Firing</h2>
 * <p>The rule guard is applied to the full argument vector; a guard fault is
 * absorbed as an unsatisfied guard. The verdict is then applied, yielding a
 * total sanction descriptor, which is materialised onto the command surface
 * and driven through the shared resolver and executor under the system
 * actor's permission predicate. An automaton that is not armed, and any
 * descriptor carrying the forced dry-run term, is evaluated in dry-run.
 * Every non-simulated firing appends an audit entry.
 *
 * <h2>Threading</h2>
 * <p>{@link #handle} is invoked from platform event threads (the main thread
 * for most kinds, the asynchronous chat thread for chat). Tracking structures
 * are backed by concurrent maps; guard evaluation reads only cached
 * environment values and performs no I/O on the calling thread. Firing
 * delegates persistence and reporting to the executor, which marshals
 * player-facing work to the main thread itself.
 */
public final class DispatchEngine {

    private static final int CAPTURE_SWEEP_EVERY = 512;

    private final KernelLogger log;
    private final SanctionResolver resolver;
    private final SanctionExecutor executor;
    private final MessageService messages;
    private final Storage storage;
    private final SafetyConfig safety;
    private final GuardEnvironment env;
    private final Supplier<CommandSender> sink;

    private final Throttle throttle;
    private final WindowTracker windows = new WindowTracker();
    private final SequenceTracker sequences = new SequenceTracker();
    private final Set<String> muted = ConcurrentHashMap.newKeySet();
    private final Map<String, List<EventFacts>> captures = new ConcurrentHashMap<>(64);
    private int captureOps;

    private final Map<EventKind, List<ArmedRule>> index = new EnumMap<>(EventKind.class);
    private final int ruleCount;

    public DispatchEngine(final KernelLogger log,
                          final List<ArmedRule> rules,
                          final SanctionResolver resolver,
                          final SanctionExecutor executor,
                          final MessageService messages,
                          final Storage storage,
                          final SafetyConfig safety,
                          final GuardEnvironment env,
                          final Supplier<CommandSender> sink) {
        this.log = log;
        this.resolver = resolver;
        this.executor = executor;
        this.messages = messages;
        this.storage = storage;
        this.safety = safety;
        this.env = env;
        this.sink = sink;
        this.throttle = new Throttle(safety.throttlePerMin());

        for (final ArmedRule rule : rules) {
            for (final EventKind kind : rule.plan().eventKinds()) {
                index.computeIfAbsent(kind, k -> new ArrayList<>()).add(rule);
            }
        }
        this.ruleCount = rules.size();
    }

    // ------------------------------------------------------------------
    // Event entry
    // ------------------------------------------------------------------

    /**
     * Handles an incoming event, firing every rule that passes gating.
     *
     * @param facts the event facts
     */
    public void handle(final EventFacts facts) {
        final List<ArmedRule> candidates = index.get(facts.kind());
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        final long now = env.now();
        for (final ArmedRule rule : candidates) {
            final Outcome outcome = gate(rule, facts, now, true);
            switch (outcome.decision()) {
                case PASS -> fire(rule, outcome.args(), facts, false, sink.get());
                case THROTTLED -> log.warn("dispatch",
                        "automaton '%s' self-muted after exceeding %d firing(s)/min",
                        rule.automaton(), safety.throttlePerMin());
                case GUARD_ERROR -> log.trace("dispatch",
                        "rule '%s' guard errored; treated as unsatisfied", rule.qualifiedName());
                case GUARD_FAIL, WINDOW_WAIT, MUTED -> {
                    // The rule did not fire on this event; no action.
                }
            }
        }
    }

    /**
     * Simulates an event in forced dry-run and reports the outcome.
     *
     * <p>Every rule is gated without consuming window, sequence, throttle, or
     * mute state; a passing rule's verdict is resolved and reported through
     * the dry-run path without applying an effect.
     *
     * @param facts the synthetic event facts
     * @param to    the report recipient
     */
    public void simulate(final EventFacts facts, final CommandSender to) {
        final List<ArmedRule> candidates = index.get(facts.kind());
        messages.send(to, MessageKey.ALAM_SIM_HEADER,
                Arg.s("kind", facts.kind().name()), Arg.s("subject", facts.subjectName()));
        if (candidates == null || candidates.isEmpty()) {
            messages.send(to, MessageKey.ALAM_SIM_EMPTY);
            return;
        }
        final long now = env.now();
        boolean any = false;
        for (final ArmedRule rule : candidates) {
            final Outcome outcome = gate(rule, facts, now, false);
            if (outcome.decision() == FireDecision.PASS) {
                any = true;
                messages.send(to, MessageKey.ALAM_SIM_FIRE,
                        Arg.s("automaton", rule.automaton()), Arg.s("rule", rule.plan().name()));
                fire(rule, outcome.args(), facts, true, to);
            } else {
                messages.send(to, MessageKey.ALAM_SIM_SKIP,
                        Arg.s("automaton", rule.automaton()), Arg.s("rule", rule.plan().name()),
                        Arg.s("decision", outcome.decision().name()));
            }
        }
        if (!any) {
            messages.send(to, MessageKey.ALAM_SIM_EMPTY);
        }
    }

    // ------------------------------------------------------------------
    // Gating
    // ------------------------------------------------------------------

    /** A gating outcome: the decision and, on a pass, the argument vector. */
    private record Outcome(FireDecision decision, List<Value> args) {

        static Outcome of(final FireDecision decision) {
            return new Outcome(decision, null);
        }
    }

    private Outcome gate(final ArmedRule rule, final EventFacts facts,
                         final long now, final boolean countFiring) {
        if (muted.contains(rule.automaton())) {
            return Outcome.of(FireDecision.MUTED);
        }
        return switch (rule.plan().trigger()) {
            case TriggerPlan.Atomic ignored -> gateSingle(rule, facts, now, countFiring, null);
            case TriggerPlan.Repeated rep -> gateSingle(rule, facts, now, countFiring, rep);
            case TriggerPlan.Sequence seq -> gateSequence(rule, seq, facts, now, countFiring);
        };
    }

    /** Gates an atomic or repeated rule; {@code rep} is null for atomic. */
    private Outcome gateSingle(final ArmedRule rule, final EventFacts facts, final long now,
                               final boolean countFiring, final TriggerPlan.Repeated rep) {
        final Value record = EventAdapter.toRecord(facts, rule.plan().eventShapes()[0]);
        final List<Value> args = List.of(record);

        final Boolean pass = guard(rule, rule.plan().guard(), args);
        if (pass == null) {
            return Outcome.of(FireDecision.GUARD_ERROR);
        }
        if (!pass) {
            return Outcome.of(FireDecision.GUARD_FAIL);
        }
        if (!countFiring) {
            return new Outcome(FireDecision.PASS, args);
        }
        if (rep != null) {
            final String key = trackKey(rule, rep.part(), facts);
            if (!windows.record(key, now, rep.count(), rep.withinMs())) {
                return Outcome.of(FireDecision.WINDOW_WAIT);
            }
        }
        return throttleGate(rule) ? new Outcome(FireDecision.PASS, args)
                : Outcome.of(FireDecision.THROTTLED);
    }

    private Outcome gateSequence(final ArmedRule rule, final TriggerPlan.Sequence seq,
                                 final EventFacts facts, final long now,
                                 final boolean countFiring) {
        if (!countFiring) {
            // Simulation admits only an event that would complete the sequence:
            // it must satisfy the final step; no partial state is consulted.
            final TriggerPlan.Sequence.Step last = seq.steps()[seq.steps().length - 1];
            if (last.kind() != facts.kind()) {
                return Outcome.of(FireDecision.WINDOW_WAIT);
            }
            final int lastIdx = seq.steps().length - 1;
            final Boolean pass = stepGuard(rule, lastIdx, facts);
            if (pass == null) {
                return Outcome.of(FireDecision.GUARD_ERROR);
            }
            if (!pass) {
                return Outcome.of(FireDecision.WINDOW_WAIT);
            }
            // Only the completing argument is real; earlier positions are
            // presented as the simulated event's facts under each step shape,
            // which is the closest total rehearsal available without state.
            return new Outcome(FireDecision.PASS, sequenceArgs(rule, List.of(), facts));
        }

        final String key = trackKey(rule, seq.part(), facts);
        final int before = sequences.stepOf(key);
        final boolean fired = sequences.advance(key, seq.steps().length, seq.withinMs(),
                facts, (stepIndex, f) -> stepMatches(rule, seq, stepIndex, f), now);
        maybeSweepCaptures();

        if (fired) {
            final List<EventFacts> captured = captures.remove(key);
            final List<Value> args = sequenceArgs(rule,
                    captured == null ? List.of() : captured, facts);
            final Boolean pass = guard(rule, rule.plan().guard(), args);
            if (pass == null) {
                return Outcome.of(FireDecision.GUARD_ERROR);
            }
            if (!pass) {
                return Outcome.of(FireDecision.GUARD_FAIL);
            }
            return throttleGate(rule) ? new Outcome(FireDecision.PASS, args)
                    : Outcome.of(FireDecision.THROTTLED);
        }

        // Synchronise the capture list with the tracker's observed transition.
        final int after = sequences.stepOf(key);
        if (after == before + 1) {
            captures.computeIfAbsent(key, k -> new ArrayList<>(4)).add(facts);
        } else if (after == 1 && before > 1) {
            final List<EventFacts> list =
                    captures.computeIfAbsent(key, k -> new ArrayList<>(4));
            list.clear();
            list.add(facts);
        }
        return Outcome.of(FireDecision.WINDOW_WAIT);
    }

    private boolean stepMatches(final ArmedRule rule, final TriggerPlan.Sequence seq,
                                final int stepIndex, final EventFacts facts) {
        if (seq.steps()[stepIndex].kind() != facts.kind()) {
            return false;
        }
        final Boolean pass = stepGuard(rule, stepIndex, facts);
        return pass != null && pass;
    }

    private Boolean stepGuard(final ArmedRule rule, final int stepIndex, final EventFacts facts) {
        final TriggerPlan.Sequence seq = (TriggerPlan.Sequence) rule.plan().trigger();
        final Value record = EventAdapter.toRecord(facts, rule.plan().eventShapes()[stepIndex]);
        try {
            return rule.interp().applyGuard(seq.steps()[stepIndex].guard(),
                    List.of(record), env);
        } catch (final Interp.Failure e) {
            return null;
        }
    }

    /** Builds the full argument vector of a sequence rule. */
    private List<Value> sequenceArgs(final ArmedRule rule, final List<EventFacts> captured,
                                     final EventFacts completing) {
        final RulePlan plan = rule.plan();
        final int n = plan.eventShapes().length;
        final List<Value> args = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final EventFacts facts = i < captured.size() ? captured.get(i) : completing;
            args.add(EventAdapter.toRecord(facts, plan.eventShapes()[i]));
        }
        return args;
    }

    private Boolean guard(final ArmedRule rule, final Value guard, final List<Value> args) {
        try {
            return rule.interp().applyGuard(guard, args, env);
        } catch (final Interp.Failure e) {
            return null;
        }
    }

    private boolean throttleGate(final ArmedRule rule) {
        if (throttle.tryAcquire(rule.automaton(), env.now())) {
            return true;
        }
        muted.add(rule.automaton());
        return false;
    }

    private static String trackKey(final ArmedRule rule, final TriggerPlan.Part part,
                                   final EventFacts facts) {
        final String partition = switch (part.kind()) {
            case SUBJECT -> String.valueOf(facts.subject());
            case GLOBAL -> "*";
            case FIELD -> String.valueOf(facts.field(part.field()));
        };
        return rule.automaton() + '|' + rule.plan().name() + '|' + partition;
    }

    private void maybeSweepCaptures() {
        if (++captureOps % CAPTURE_SWEEP_EVERY != 0) {
            return;
        }
        captures.keySet().removeIf(key -> sequences.stepOf(key) == 0);
    }

    // ------------------------------------------------------------------
    // Firing
    // ------------------------------------------------------------------

    private void fire(final ArmedRule rule, final List<Value> args, final EventFacts facts,
                      final boolean simulate, final CommandSender issuer) {
        final Value.RecordV sanction;
        try {
            sanction = rule.interp().applyVerdict(rule.plan().verdict(), args, env);
        } catch (final Interp.Failure e) {
            log.warn("dispatch", "rule '%s' verdict failed: %s",
                    rule.qualifiedName(), e.getMessage());
            return;
        }
        final SanctionMaterializer.Materialized m =
                SanctionMaterializer.materialize(sanction, facts);

        final boolean dryRun = simulate || m.forcedDryRun() || !safety.isArmed(rule.automaton());
        final ParsedCommand command = withPosture(m, dryRun);
        try {
            final ResolvedSanction resolved =
                    resolver.resolve(command, safety.systemActor().predicate());
            executor.execute(resolved, issuer);
        } catch (final ResolveException e) {
            if (e.hasKey()) {
                messages.send(issuer, e.key(), e.args());
            } else {
                messages.send(issuer, MessageKey.ERROR_RESOLVE,
                        Arg.s("error", e.getMessage()));
            }
            return;
        } catch (final RuntimeException e) {
            log.warn("dispatch", "rule '%s' failed during firing: %s",
                    rule.qualifiedName(), e.getMessage());
            return;
        }
        if (!simulate) {
            audit(rule, facts, dryRun);
        }
    }

    /**
     * Applies the engine's posture to a materialised command: system
     * attribution when the descriptor requests it and the actor holds the
     * attribution grant, and the dry-run directive when the firing is not
     * live. The materialised annotation list is copied, never mutated.
     */
    private ParsedCommand withPosture(final SanctionMaterializer.Materialized m,
                                      final boolean dryRun) {
        final ParsedCommand base = m.command();
        final List<RawAnnotation> annotations = new ArrayList<>(base.annotations());
        if (m.systemAttribution() && safety.systemActor().name() != null
                && safety.systemActor().has("minutiae.annotation.as")) {
            annotations.add(new RawAnnotation(false, "as",
                    List.of(safety.systemActor().name()), Map.of()));
        }
        if (dryRun) {
            boolean present = false;
            for (final RawAnnotation a : annotations) {
                if (a.name().equals("dry-run")) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                annotations.add(new RawAnnotation(false, "dry-run", List.of(), Map.of()));
            }
        }
        return new ParsedCommand(base.target(), base.layoutKey(),
                List.copyOf(annotations), base.overrides());
    }

    private void audit(final ArmedRule rule, final EventFacts facts, final boolean dryRun) {
        final String detail = "automaton=" + rule.automaton()
                + " rule=" + rule.plan().name()
                + " event=" + facts.kind()
                + " target=" + facts.subjectName()
                + (dryRun ? " (dry-run)" : "");
        storage.recordAudit(null, "DISPATCH", safety.systemActor().name(), env.now(), detail)
                .exceptionally(err -> {
                    log.error("dispatch", err, "audit write failed for '%s'",
                            rule.qualifiedName());
                    return null;
                });
    }

    // ------------------------------------------------------------------
    // Inspection
    // ------------------------------------------------------------------

    /** Returns the armed rules bound to an event kind. */
    public List<ArmedRule> rulesFor(final EventKind kind) {
        return index.getOrDefault(kind, List.of());
    }

    /** Returns the total number of armed rules. */
    public int ruleCount() {
        return ruleCount;
    }

    /** Reports whether an automaton is currently self-muted. */
    public boolean isMuted(final String automaton) {
        return muted.contains(automaton);
    }
}