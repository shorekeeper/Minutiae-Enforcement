package org.synergyst.minutiae.layout;

import org.synergyst.minutiae.annotation.RawAnnotation;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.time.DurationSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves layout inheritance into flattened {@link Layout} instances.
 *
 * <p>Resolution proceeds by depth-first traversal with three-colour marking to
 * detect cycles. A layout fails resolution when it participates in an
 * inheritance cycle, references a missing parent, or descends from a failed
 * ancestor. Failure is isolated: a failed layout is logged and omitted from the
 * output while unrelated layouts resolve normally.
 *
 * <p>Merge semantics: each nullable scalar field takes the child value when
 * present and the resolved parent value otherwise. The escalation ladder is
 * replaced wholesale by the child when present. Annotations are concatenated as
 * {@code parent-annotations ++ child-annotations}, preserving order and
 * duplicates; semantic de-duplication and conflict handling are out of scope
 * for structural resolution.
 *
 * <p>An instance is single-use: {@link #resolve()} mutates internal traversal
 * state and must be invoked at most once per instance.
 */
public final class LayoutResolver {

    private static final int UNVISITED = 0;
    private static final int IN_PROGRESS = 1;
    private static final int DONE = 2;

    private static final DurationSpec[] EMPTY_ESCALATION = new DurationSpec[0];
    private static final RawAnnotation[] EMPTY_ANNOTATIONS = new RawAnnotation[0];

    private final KernelLogger log;
    private final Map<String, LayoutDefinition> definitions;
    private final Map<String, Integer> colour;
    private final Map<String, Layout> resolved;

    private int failures;

    public LayoutResolver(final KernelLogger log, final Map<String, LayoutDefinition> definitions) {
        this.log = log;
        this.definitions = definitions;
        this.colour = new HashMap<>(definitions.size() * 2);
        this.resolved = new HashMap<>(definitions.size() * 2);
    }

    /**
     * Resolves every definition supplied at construction.
     *
     * @return a map from key to resolved layout, containing only successfully
     *         resolved entries
     */
    public Map<String, Layout> resolve() {
        for (final String key : definitions.keySet()) {
            resolveKey(key);
        }
        final Map<String, Layout> out = new HashMap<>(resolved.size() * 2);
        for (final Map.Entry<String, Layout> e : resolved.entrySet()) {
            if (e.getValue() != null) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /**
     * Returns the number of layouts that failed structural resolution.
     *
     * @return the failure count
     */
    public int failures() {
        return failures;
    }

    private Layout resolveKey(final String key) {
        final Integer state = colour.get(key);
        if (state != null) {
            if (state == DONE) {
                return resolved.get(key);
            }
            if (state == IN_PROGRESS) {
                log.error("layouts", "layout '%s' participates in an inheritance cycle", key);
                return fail(key);
            }
        }

        colour.put(key, IN_PROGRESS);
        final LayoutDefinition def = definitions.get(key);

        Layout parent = null;
        if (def.extendsKey() != null) {
            if (!definitions.containsKey(def.extendsKey())) {
                log.error("layouts", "layout '%s' extends unknown parent '%s'",
                        key, def.extendsKey());
                return fail(key);
            }
            parent = resolveKey(def.extendsKey());
            if (parent == null) {
                log.error("layouts", "layout '%s' disabled: parent '%s' failed to resolve",
                        key, def.extendsKey());
                return fail(key);
            }
        }

        final Layout merged = merge(def, parent);
        colour.put(key, DONE);
        resolved.put(key, merged);
        return merged;
    }

    private Layout fail(final String key) {
        colour.put(key, DONE);
        resolved.put(key, null);
        failures++;
        return null;
    }

    private Layout merge(final LayoutDefinition def, final Layout parent) {
        final String rule = def.rule() != null
                ? def.rule() : (parent != null ? parent.rule() : null);
        final String reason = def.reason() != null
                ? def.reason() : (parent != null ? parent.reason() : null);
        final org.synergyst.minutiae.measure.Measure measure = def.measure() != null
                ? def.measure() : (parent != null ? parent.measure() : null);
        final DurationSpec duration = def.duration() != null
                ? def.duration() : (parent != null ? parent.duration() : null);
        final DurationSpec[] escalation = def.escalation() != null
                ? def.escalation() : (parent != null ? parent.escalation() : EMPTY_ESCALATION);
        final RawAnnotation[] annotations = concat(
                parent != null ? parent.annotations() : EMPTY_ANNOTATIONS, def.annotations());

        return new Layout(def.key(), def.isPrivate(), rule, reason, measure, duration, escalation, annotations);
    }

    private static RawAnnotation[] concat(final RawAnnotation[] head, final List<RawAnnotation> tail) {
        final RawAnnotation[] out = new RawAnnotation[head.length + tail.size()];
        System.arraycopy(head, 0, out, 0, head.length);
        for (int i = 0; i < tail.size(); i++) {
            out[head.length + i] = tail.get(i);
        }
        return out;
    }

    /** Utility for callers assembling an empty annotation list. */
    public static List<RawAnnotation> emptyAnnotationList() {
        return new ArrayList<>(0);
    }
}