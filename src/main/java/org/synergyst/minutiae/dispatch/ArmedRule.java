package org.synergyst.minutiae.dispatch;

import org.synergyst.minutiae.lang.plan.RulePlan;
import org.synergyst.minutiae.lang.run.Interp;

/**
 * One armed rule: a plan bound to its owning automaton and to the interpreter
 * context of the unit that produced it.
 *
 * <p>The automaton name scopes arming, throttling, and self-muting; the
 * interpreter context is the only object through which the plan's guard,
 * step-guard, and verdict closures may be evaluated. Instances are immutable
 * and safe for concurrent read.
 *
 * @param automaton owning automaton name
 * @param plan      the rule plan
 * @param interp    the interpreter context of the producing unit
 */
public record ArmedRule(String automaton, RulePlan plan, Interp interp) {

    /** Returns the qualified display name used in reporting and audit. */
    public String qualifiedName() {
        return automaton + "." + plan.name();
    }
}