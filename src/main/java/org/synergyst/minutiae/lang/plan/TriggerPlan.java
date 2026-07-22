package org.synergyst.minutiae.lang.plan;

import org.synergyst.minutiae.engine.EventKind;
import org.synergyst.minutiae.lang.eval.Value;

/**
 * The firing precondition of a rule, reduced to primitive data.
 *
 * <p>The three forms map one-to-one onto the runtime tracking machinery: an
 * atomic trigger requires no state; a repeated trigger drives a sliding
 * recurrence window; a sequence trigger drives a partial-match tracker whose
 * per-step admission is decided by the step guard values carried here.
 *
 * <p>All spans are milliseconds and are verified positive at plan
 * construction. Partitioning is expressed by {@link Part}; the dispatch
 * engine renders the partition key from the incoming event facts.
 */
public sealed interface TriggerPlan
        permits TriggerPlan.Atomic, TriggerPlan.Repeated, TriggerPlan.Sequence {

    /** Partition-key selector of a windowed trigger. */
    record Part(Kind kind, String field) {

        /** Selector kinds. The set is closed. */
        public enum Kind {

            /** Partition by the event subject account. */
            SUBJECT,

            /** A single global partition. */
            GLOBAL,

            /** Partition by a named event field, rendered as text. */
            FIELD
        }

        /** The subject-partition selector. */
        public static final Part SUBJECT = new Part(Kind.SUBJECT, null);

        /** The global-partition selector. */
        public static final Part GLOBAL = new Part(Kind.GLOBAL, null);
    }

    /** A stateless trigger admitting every occurrence of its event. */
    record Atomic() implements TriggerPlan {

        /** The single instance; the form carries no data. */
        public static final Atomic INSTANCE = new Atomic();
    }

    /**
     * A recurrence trigger: fires once its event has recurred {@code count}
     * times within the trailing span, accrued per partition.
     *
     * @param count    required recurrence count, in {@code [1, 10000]}
     * @param withinMs trailing span in milliseconds, positive
     * @param part     partition selector
     */
    record Repeated(int count, long withinMs, Part part) implements TriggerPlan {
    }

    /**
     * A sequence trigger: fires once its steps have been observed in order
     * within the span, per partition.
     *
     * <p>Each step binds an event kind to a guard value of one parameter; the
     * dispatch engine evaluates the guard against the incoming facts to decide
     * step admission. The guard value is either a closure or the always-true
     * guard and is evaluated through the plan's shared interpreter.
     *
     * @param withinMs completion span in milliseconds, positive
     * @param part     partition selector
     * @param steps    ordered steps, at least two
     */
    record Sequence(long withinMs, Part part, Step[] steps) implements TriggerPlan {

        /** One sequence step: an event kind and its admission guard. */
        public record Step(EventKind kind, Value guard) {
        }
    }
}