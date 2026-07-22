/**
 * Residual execution plans of the definition language.
 *
 * <p>A plan is the first-order artifact that survives compilation and is the
 * only shape the dispatch engine ever touches. Plans are immutable, carry no
 * source references beyond names used in reporting, and are produced in full
 * before any rule is armed: a source either yields a complete verified plan
 * or is rejected whole.
 *
 * <p>The package separates three concerns:
 * <ul>
 *   <li>{@link org.synergyst.minutiae.lang.plan.TriggerPlan} - the firing
 *       precondition, reduced to primitive data (counts, millisecond spans,
 *       partition selectors, per-step guard values);</li>
 *   <li>{@link org.synergyst.minutiae.lang.plan.RulePlan} - one armed
 *       implication: trigger data plus the guard and verdict function values
 *       whose bodies the runtime interpreter evaluates per event;</li>
 *   <li>{@link org.synergyst.minutiae.lang.plan.UnitPlan} - the whole unit:
 *       stamped layout definitions and named automata resolved to ordered
 *       rule lists, together with the interpreter context the plans share.</li>
 * </ul>
 *
 * <p>Verification and lowering guarantee the following invariants on every
 * published plan, so the dispatch engine performs no validation of its own:
 * every trigger span is a positive finite number of milliseconds; every
 * recurrence count lies in {@code [1, 10000]}; every sequence has at least two
 * steps and its step events equal the rule's declared events position-wise;
 * every rule-identifier literal reachable from a guard or verdict body names
 * a rule known to the rule registry; every layout descriptor satisfies the
 * measure-duration temporal invariant and carries only configuration-scoped
 * annotations.
 */
package org.synergyst.minutiae.lang.plan;