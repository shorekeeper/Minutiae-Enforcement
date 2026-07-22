package org.synergyst.minutiae.lang.plan;

import org.synergyst.minutiae.engine.EventKind;
import org.synergyst.minutiae.lang.eval.Value;
import org.synergyst.minutiae.lang.types.Type;

import java.util.List;

/**
 * One armed rule: reduced trigger data plus the guard and verdict function
 * values evaluated at dispatch time.
 *
 * <p>The guard and the verdict take one argument per declared event, in
 * declaration order. For atomic and repeated triggers that is a single
 * argument. For sequence triggers the dispatch engine captures the facts of
 * each admitted step per partition and, on completion, applies the guard and
 * the verdict to the full captured argument list; a sequence rule therefore
 * observes every participating event, not only the completing one.
 *
 * <p>{@code eventShapes} carries, per position, the record type under which
 * the corresponding event's facts are presented to the interpreter. The
 * shapes are derived from the event catalogue at lowering; presenting facts
 * as ordinary record values lets guard and verdict bodies use plain field
 * access with no event-specific evaluation path.
 *
 * <p>The guard is a query-capable value: its body may call the environment
 * built-ins. The verdict is pure and yields a total sanction descriptor. Any
 * evaluation failure in either is absorbed by the dispatch engine as a
 * non-firing, consistent with the fail-safe posture of enforcement.
 *
 * @param name        rule name, used in reporting and audit
 * @param eventKinds  observed event kinds, in declaration order
 * @param eventShapes record shapes of the event arguments, parallel to
 *                    {@code eventKinds}
 * @param trigger     reduced trigger data
 * @param guard       guard value: a closure or the always-true guard
 * @param verdict     verdict value: a closure yielding a sanction descriptor
 */
public record RulePlan(String name,
                       EventKind[] eventKinds,
                       Type.Rec[] eventShapes,
                       TriggerPlan trigger,
                       Value guard,
                       Value verdict) {

    /** Returns the principal event kind: the first declared event. */
    public EventKind primaryKind() {
        return eventKinds[0];
    }
}