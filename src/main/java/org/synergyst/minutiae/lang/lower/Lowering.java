package org.synergyst.minutiae.lang.lower;

import org.synergyst.minutiae.annotation.RawAnnotation;
import org.synergyst.minutiae.engine.EventKind;
import org.synergyst.minutiae.lang.eval.Evaluator;
import org.synergyst.minutiae.lang.eval.Value;
import org.synergyst.minutiae.lang.plan.RulePlan;
import org.synergyst.minutiae.lang.plan.TriggerPlan;
import org.synergyst.minutiae.lang.run.Interp;
import org.synergyst.minutiae.lang.types.Type;
import org.synergyst.minutiae.layout.LayoutDefinition;
import org.synergyst.minutiae.measure.Measure;
import org.synergyst.minutiae.time.DurationSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lowers verified residual values onto the runtime vocabulary.
 *
 * <p>Lowering is a pure structural translation with no failure modes of its
 * own: every shape it consumes was established by elaboration and every
 * dynamic bound by verification. It produces two artifact families:
 * <ul>
 *   <li>{@link LayoutDefinition} values, ingested by the layout registry
 *       through the same path as file-authored definitions;</li>
 *   <li>{@link RulePlan} values grouped per automaton, consumed by the
 *       dispatch engine.</li>
 * </ul>
 *
 * <p>Event names are mapped onto the runtime {@link EventKind} enumeration;
 * the mapping is total because the event catalogue and the enumeration are
 * maintained together. Trigger variants reduce to primitive plan data;
 * duration terms reduce to millisecond spans; the {@code GroupBy} variants
 * reduce to partition selectors. Event record shapes are interned per event
 * so that all plans of a unit share one shape instance per event type.
 */
public final class Lowering {

    private final Map<String, Type.Rec> shapeCache = new HashMap<>(8);

    private Lowering() {
    }

    /**
     * Lowers layouts and automata of one verified unit.
     *
     * @param out    the evaluator output
     * @param interp the interpreter context the plans will share
     * @return the lowered automata, name to ordered rule plans
     */
    public static LinkedHashMap<String, List<RulePlan>> lowerAutomata(
            final Evaluator.Output out, final Interp interp) {
        final Lowering lowering = new Lowering();
        final LinkedHashMap<String, List<RulePlan>> automata = new LinkedHashMap<>();
        for (final Map.Entry<String, List<Value.RuleV>> e : out.automata().entrySet()) {
            final List<RulePlan> plans = new ArrayList<>(e.getValue().size());
            for (final Value.RuleV rule : e.getValue()) {
                plans.add(lowering.lowerRule(rule));
            }
            automata.put(e.getKey(), List.copyOf(plans));
        }
        return automata;
    }

    /**
     * Lowers stamped layout descriptors into registry definitions.
     *
     * @param out the evaluator output
     * @return the layout definitions, in production order
     */
    public static List<LayoutDefinition> lowerLayouts(final Evaluator.Output out) {
        final List<LayoutDefinition> defs = new ArrayList<>(out.layouts().size());
        for (final Value.RecordV layout : out.layouts()) {
            defs.add(lowerLayout(layout));
        }
        return List.copyOf(defs);
    }

    // ------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------

    private RulePlan lowerRule(final Value.RuleV rule) {
        final int n = rule.events().size();
        final EventKind[] kinds = new EventKind[n];
        final Type.Rec[] shapes = new Type.Rec[n];
        for (int i = 0; i < n; i++) {
            final Type.Event ev = rule.events().get(i);
            kinds[i] = kindOf(ev.name());
            shapes[i] = shapeOf(ev);
        }
        return new RulePlan(rule.name(), kinds, shapes,
                lowerTrigger(rule.trigger()), rule.guard(), rule.verdict());
    }

    private TriggerPlan lowerTrigger(final Value.VariantV trigger) {
        return switch (trigger.ctor()) {
            case "Atomic" -> TriggerPlan.Atomic.INSTANCE;
            case "Repeated" -> new TriggerPlan.Repeated(
                    (int) ((Value.IntV) trigger.field(0)).value(),
                    ((Value.DurV) trigger.field(1)).value().millis(),
                    partOf((Value.VariantV) trigger.field(2)));
            case "Sequence" -> {
                final List<Value> raw = ((Value.ListV) trigger.field(2)).items();
                final TriggerPlan.Sequence.Step[] steps =
                        new TriggerPlan.Sequence.Step[raw.size()];
                for (int i = 0; i < raw.size(); i++) {
                    final Value.StepV step = (Value.StepV) raw.get(i);
                    steps[i] = new TriggerPlan.Sequence.Step(
                            kindOf(step.event().name()), step.guard());
                }
                yield new TriggerPlan.Sequence(
                        ((Value.DurV) trigger.field(0)).value().millis(),
                        partOf((Value.VariantV) trigger.field(1)), steps);
            }
            // The trigger sum is closed and was validated at evaluation.
            default -> throw new IllegalStateException(trigger.ctor());
        };
    }

    private static TriggerPlan.Part partOf(final Value.VariantV groupBy) {
        return switch (groupBy.ctor()) {
            case "Subject" -> TriggerPlan.Part.SUBJECT;
            case "Global" -> TriggerPlan.Part.GLOBAL;
            default -> new TriggerPlan.Part(TriggerPlan.Part.Kind.FIELD,
                    ((Value.TextV) groupBy.field(0)).value());
        };
    }

    private static EventKind kindOf(final String eventName) {
        // The catalogue and the enumeration are maintained together; the
        // upper-cased event name is the enumeration constant by convention.
        return EventKind.valueOf(eventName.toUpperCase(Locale.ROOT));
    }

    private Type.Rec shapeOf(final Type.Event event) {
        return shapeCache.computeIfAbsent(event.name(),
                name -> new Type.Rec(name, event.fields()));
    }

    // ------------------------------------------------------------------
    // Layouts
    // ------------------------------------------------------------------

    private static LayoutDefinition lowerLayout(final Value.RecordV layout) {
        final String key = ((Value.TextV) layout.field("key")).value();
        final String rule = ((Value.RuleIdV) layout.field("rule")).id();
        final String reason = ((Value.TextV) layout.field("reason")).value();
        final Measure measure = Measure.parse(((Value.VariantV) layout.field("measure")).ctor());

        // The temporal invariant was verified: a Fixed term appears exactly
        // when the measure is temporal, and lowers to its span; both other
        // classes lower to the absence of a declared duration.
        final Value.VariantV durationTerm = (Value.VariantV) layout.field("duration");
        final DurationSpec duration = durationTerm.ctor().equals("Fixed")
                ? ((Value.DurV) durationTerm.field(0)).value()
                : null;

        final Value.VariantV escalation = (Value.VariantV) layout.field("escalation");
        final DurationSpec[] ladder;
        if (escalation.ctor().equals("Steps")) {
            final List<Value> steps = ((Value.ListV) escalation.field(0)).items();
            ladder = new DurationSpec[steps.size()];
            for (int i = 0; i < steps.size(); i++) {
                final Value.VariantV rung = (Value.VariantV) steps.get(i);
                ladder[i] = rung.ctor().equals("Permanent")
                        ? DurationSpec.PERMANENT
                        : ((Value.DurV) rung.field(0)).value();
            }
        } else {
            ladder = null;
        }

        final List<RawAnnotation> annotations = new ArrayList<>(4);
        for (final Value a : ((Value.ListV) layout.field("annotations")).items()) {
            annotations.add(annotationToken((Value.VariantV) a));
        }
        return new LayoutDefinition(key, false, null, rule, reason,
                measure, duration, ladder, List.copyOf(annotations));
    }

    /**
     * Renders one annotation variant as its configuration-surface token.
     * Command-scoped variants were rejected by verification and cannot occur.
     */
    private static RawAnnotation annotationToken(final Value.VariantV a) {
        return switch (a.ctor()) {
            case "Notify" -> new RawAnnotation(false, "notify",
                    List.of(((Value.TextV) a.field(0)).value()), Map.of());
            case "WarnFirst" -> new RawAnnotation(false, "warn-first",
                    List.of(Long.toString(((Value.IntV) a.field(0)).value())), Map.of());
            case "Decay" -> spanToken("decay", a);
            case "Tariff" -> spanToken("tariff", a);
            case "Probation" -> spanToken("probation", a);
            case "Evidence" -> new RawAnnotation(false, "evidence", List.of(), Map.of());
            default -> new RawAnnotation(false,
                    a.ctor().toLowerCase(Locale.ROOT), List.of(), Map.of());
        };
    }

    private static RawAnnotation spanToken(final String name, final Value.VariantV a) {
        return new RawAnnotation(false, name,
                List.of(((Value.DurV) a.field(0)).value().format()), Map.of());
    }
}