package org.synergyst.minutiae.lang;

import org.synergyst.minutiae.lang.diag.Diagnostic;
import org.synergyst.minutiae.lang.eval.Value;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The residual result of compiling one source of the definition language.
 *
 * <p>A unit is immutable and safe to hand across threads. When {@code ok} is
 * false the artifact collections are empty and only the diagnostics are
 * meaningful; a unit is never partially armed.
 *
 * @param origin        source identifier used in operator-facing reporting
 * @param constants     named constants by declaration order
 * @param layouts       layout descriptors stamped by expansion, in order
 * @param rules         declared rules by name, in declaration order
 * @param expandedRules rules produced by expansion, in production order
 * @param automata      resolved automata: name to ordered rule list
 * @param diagnostics   diagnostics of all phases, in report order
 * @param ok            whether no error-severity diagnostic was reported
 */
public record CompiledUnit(String origin,
                           Map<String, Value> constants,
                           List<Value.RecordV> layouts,
                           LinkedHashMap<String, Value.RuleV> rules,
                           List<Value.RuleV> expandedRules,
                           LinkedHashMap<String, List<Value.RuleV>> automata,
                           List<Diagnostic> diagnostics,
                           boolean ok) {

    /** Returns an empty unit carrying only diagnostics. */
    public static CompiledUnit failed(final String origin, final List<Diagnostic> diagnostics) {
        return new CompiledUnit(origin, Map.of(), List.of(), new LinkedHashMap<>(),
                List.of(), new LinkedHashMap<>(), diagnostics, false);
    }
}