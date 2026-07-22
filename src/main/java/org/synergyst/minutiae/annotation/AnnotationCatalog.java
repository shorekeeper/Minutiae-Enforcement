package org.synergyst.minutiae.annotation;

import java.util.HashMap;
import java.util.Map;

import static org.synergyst.minutiae.annotation.AnnotationSpec.Scope.CONFIG_AND_INLINE;
import static org.synergyst.minutiae.annotation.AnnotationSpec.Scope.INLINE_ONLY;

/**
 * Immutable catalogue of built-in annotations.
 *
 * <p>Specifications are held in an ordinal-indexed array for constant-time
 * resolution by set bit, and in a name map for constant-time resolution by
 * identifier. Ordinals are assigned densely in declaration order; the catalogue
 * size is capped at 64 so that any annotation set fits in a single {@code long}.
 *
 * <p>Each entry declares its parameter hint and validator side by side: the
 * hint drives tab completion, the validator drives acceptance. Keeping the two
 * on one declaration line is deliberate, so a schema change cannot silently
 * leave a stale hint behind.
 *
 * <p>Permission nodes follow the convention {@code minutiae.annotation.<name>}.
 */
public final class AnnotationCatalog {

    private static final int MAX_ANNOTATIONS = 64;
    private static final String PERMISSION_PREFIX = "minutiae.annotation.";

    private final AnnotationSpec[] byOrdinal;
    private final Map<String, AnnotationSpec> byName;

    private AnnotationCatalog(final AnnotationSpec[] byOrdinal, final Map<String, AnnotationSpec> byName) {
        this.byOrdinal = byOrdinal;
        this.byName = byName;
    }

    /**
     * Constructs the built-in catalogue.
     *
     * @return a fully populated catalogue
     */
    public static AnnotationCatalog builtIn() {
        final Builder b = new Builder();

        // Behavioural annotations: participate in the relationship matrix.
        b.add("shadow", CONFIG_AND_INLINE, ParamHint.NONE, ParamSchemas.flag());
        b.add("ip-lock", CONFIG_AND_INLINE, ParamHint.NONE, ParamSchemas.flag());
        b.add("silent", CONFIG_AND_INLINE, ParamHint.NONE, ParamSchemas.flag());
        b.add("evidence", CONFIG_AND_INLINE, ParamHint.EVIDENCE, ParamSchemas.evidence());
        b.add("notify", CONFIG_AND_INLINE, ParamHint.CHANNELS,
                ParamSchemas.oneOrMorePositional("channel"));
        b.add("warn-first", CONFIG_AND_INLINE, ParamHint.POSITIVE_INT, ParamSchemas.positiveInt());
        b.add("escalate", CONFIG_AND_INLINE, ParamHint.NONE, ParamSchemas.flag());
        b.add("appeal", CONFIG_AND_INLINE, ParamHint.TOGGLE, ParamSchemas.oneOf("on", "off"));
        b.add("decay", CONFIG_AND_INLINE, ParamHint.DURATION, ParamSchemas.duration());
        b.add("ghost", CONFIG_AND_INLINE, ParamHint.NONE, ParamSchemas.flag());
        b.add("rubberband", CONFIG_AND_INLINE, ParamHint.NONE, ParamSchemas.flag());

        // Inline directives.
        b.add("now", INLINE_ONLY, ParamHint.NONE, ParamSchemas.flag());
        b.add("dry-run", INLINE_ONLY, ParamHint.NONE, ParamSchemas.flag());
        b.add("as", INLINE_ONLY, ParamHint.STAFF_NAME, ParamSchemas.singlePositional("staff name"));
        b.add("backdate", INLINE_ONLY, ParamHint.DURATION, ParamSchemas.duration());
        b.add("link", INLINE_ONLY, ParamHint.REFERENCE, ParamSchemas.singlePositional("reference"));
        b.add("reason", INLINE_ONLY, ParamHint.TEXT, ParamSchemas.singlePositional("text"));
        b.add("stay", INLINE_ONLY, ParamHint.DURATION, ParamSchemas.duration());
        b.add("tariff", CONFIG_AND_INLINE, ParamHint.DURATION, ParamSchemas.duration());
        b.add("waive", INLINE_ONLY, ParamHint.ANNOTATION_NAME,
                ParamSchemas.singlePositional("annotation name"));

        // Measure directives: consumed by the resolver, never behavioural flags.
        b.add("measure", INLINE_ONLY, ParamHint.MEASURE, MEASURE_PARAM);
        b.add("commute", INLINE_ONLY, ParamHint.MEASURE, MEASURE_PARAM);
        b.add("count", INLINE_ONLY, ParamHint.RULE_ID,
                ParamSchemas.singlePositional("rule identifier"));
        b.add("remand", INLINE_ONLY, ParamHint.NONE, ParamSchemas.flag());

        // Case-management directives and modifiers.
        b.add("amend", INLINE_ONLY, ParamHint.SANCTION_ID, ParamSchemas.positiveInt());
        b.add("weave", INLINE_ONLY, ParamHint.SANCTION_ID,
                ParamSchemas.singlePositional("sanction id"));
        b.add("probation", CONFIG_AND_INLINE, ParamHint.DURATION, ParamSchemas.duration());
        // Requests capture and persistence of the subject's recent chat lines.
        b.add("transcript", CONFIG_AND_INLINE, ParamHint.NONE, ParamSchemas.flag());

        // Procedural directives: post-issuance lifecycle of a sanction.
        b.add("note", INLINE_ONLY, ParamHint.TEXT, ParamSchemas.singlePositional("text"));
        b.add("expunge", CONFIG_AND_INLINE, ParamHint.DURATION, ParamSchemas.duration());
        b.add("review", CONFIG_AND_INLINE, ParamHint.DURATION, ParamSchemas.duration());
        b.add("suspended", CONFIG_AND_INLINE, ParamHint.DURATION, ParamSchemas.duration());

        return b.build();

    }

    /** Validator accepting exactly one positional parameter naming a measure. */
    private static final ParamValidator MEASURE_PARAM = a -> {
        final String single = ParamSchemas.singlePositional("measure").validate(a);
        if (single != null) {
            return single;
        }
        try {
            org.synergyst.minutiae.measure.Measure.parse(a.positional().get(0));
            return null;
        } catch (final IllegalArgumentException e) {
            return e.getMessage();
        }
    };

    /** Returns the number of catalogued annotations. */
    public int size() {
        return byOrdinal.length;
    }

    /**
     * Resolves a specification by ordinal.
     *
     * @param ordinal the ordinal
     * @return the specification
     */
    public AnnotationSpec byOrdinal(final int ordinal) {
        return byOrdinal[ordinal];
    }

    /**
     * Resolves a specification by name.
     *
     * @param name annotation name without sigil
     * @return the specification, or {@code null} if unknown
     */
    public AnnotationSpec byName(final String name) {
        return byName.get(name);
    }

    /**
     * Returns the ordinal-indexed specification array. The returned reference is
     * the live internal array and must be treated as read-only.
     *
     * @return the specification array
     */
    public AnnotationSpec[] all() {
        return byOrdinal;
    }

    private static final class Builder {
        private final Map<String, AnnotationSpec> map = new HashMap<>(64);
        private int next;

        void add(final String name, final AnnotationSpec.Scope scope,
                 final ParamHint hint, final ParamValidator validator) {
            if (next >= MAX_ANNOTATIONS) {
                throw new IllegalStateException("annotation catalogue exceeds " + MAX_ANNOTATIONS);
            }
            final AnnotationSpec spec = new AnnotationSpec(
                    next, name, PERMISSION_PREFIX + name, scope, hint, validator);
            if (map.putIfAbsent(name, spec) != null) {
                throw new IllegalStateException("duplicate annotation name: " + name);
            }
            next++;
        }

        AnnotationCatalog build() {
            final AnnotationSpec[] arr = new AnnotationSpec[next];
            for (final AnnotationSpec s : map.values()) {
                arr[s.ordinal()] = s;
            }
            return new AnnotationCatalog(arr, Map.copyOf(map));
        }
    }
}