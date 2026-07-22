package org.synergyst.minutiae.lang.plan;

import org.synergyst.minutiae.lang.run.Interp;
import org.synergyst.minutiae.layout.LayoutDefinition;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * The complete residual plan of one compiled source.
 *
 * <p>The plan is immutable after construction and safe to publish across
 * threads. {@code interp} is the shared interpreter context under which every
 * guard, step guard, and verdict of the plan must be evaluated; it carries
 * the elaboration side tables and the template store the closures reference.
 *
 * @param origin   source identifier used in reporting
 * @param layouts  layout definitions stamped by expansion, in production order
 * @param automata resolved automata: name to ordered rule plans
 * @param interp   the interpreter context shared by the plan's closures
 */
public record UnitPlan(String origin,
                       List<LayoutDefinition> layouts,
                       LinkedHashMap<String, List<RulePlan>> automata,
                       Interp interp) {

    /** Returns the total number of rule plans across all automata. */
    public int ruleCount() {
        int n = 0;
        for (final List<RulePlan> plans : automata.values()) {
            n += plans.size();
        }
        return n;
    }
}