package org.synergyst.minutiae.resolve;

import org.synergyst.minutiae.message.Arg;
import org.synergyst.minutiae.message.MessageKey;

import org.synergyst.minutiae.annotation.AnnotationCatalog;
import org.synergyst.minutiae.annotation.AnnotationMatrix;
import org.synergyst.minutiae.annotation.AnnotationRegistry;
import org.synergyst.minutiae.annotation.AnnotationSpec;
import org.synergyst.minutiae.annotation.RawAnnotation;
import org.synergyst.minutiae.command.parse.ParsedCommand;
import org.synergyst.minutiae.layout.Layout;
import org.synergyst.minutiae.layout.LayoutRegistry;
import org.synergyst.minutiae.measure.Measure;
import org.synergyst.minutiae.rule.RuleRegistry;
import org.synergyst.minutiae.time.DurationSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Resolves a {@link ParsedCommand} into a {@link ResolvedSanction}.
 *
 * <p>The pipeline binds an optional layout, partitions inline tokens into
 * directives and behavioural annotations, resolves the effective measure with
 * directional permission gating, merges behavioural annotations, enforces
 * permissions, validates relationships, resolves the base duration, and derives
 * the reason and auxiliary directives. When an {@code @amend} directive is
 * present the pipeline short-circuits to an amend description, which carries no
 * measure and mutates an existing sanction.
 *
 * <p>The resolver performs no I/O and holds only immutable registries; it is
 * pure and safe for concurrent use.
 */
public final class SanctionResolver {

    private static final Set<String> KNOWN_OVERRIDES = Set.of("duration", "reason");
    private static final Set<String> DIRECTIVES =
            Set.of("measure", "commute", "count", "remand", "waive", "amend");

    private final AnnotationRegistry annotations;
    private final LayoutRegistry layouts;
    private final RuleRegistry rules;

    public SanctionResolver(final AnnotationRegistry annotations,
                            final LayoutRegistry layouts,
                            final RuleRegistry rules) {
        this.annotations = annotations;
        this.layouts = layouts;
        this.rules = rules;
    }

    /**
     * Resolves a parsed command against a sender's permissions.
     *
     * @param cmd  the parsed command
     * @param perm predicate indicating whether the sender holds a permission
     * @return the resolved sanction
     * @throws ResolveException on any semantic violation
     */
    public ResolvedSanction resolve(final ParsedCommand cmd, final Predicate<String> perm) {
        final AnnotationCatalog catalog = annotations.catalog();
        final AnnotationMatrix matrix = annotations.matrix();

        final Layout layout = bindLayout(cmd.layoutKey());

        Measure measureDirective = null;
        Measure commuteDirective = null;
        boolean remand = false;
        long amendId = 0L;
        final List<String> counts = new ArrayList<>(4);
        final List<RawAnnotation> behavioural = new ArrayList<>(cmd.annotations().size());
        boolean countPermChecked = false;
        long waivedMask = 0L;

        for (final RawAnnotation a : cmd.annotations()) {
            if (!DIRECTIVES.contains(a.name())) {
                behavioural.add(a);
                continue;
            }
            if (a.negated()) {
                throw new ResolveException(MessageKey.RESOLVE_DIRECTIVE_NEGATED, Arg.s("name", a.name()));
            }
            final AnnotationSpec dspec = catalog.byName(a.name());
            final String derr = dspec.validator().validate(a);
            if (derr != null) {
                throw new ResolveException("@" + a.name() + " " + derr);
            }

            switch (a.name()) {
                case "measure" -> {
                    if (measureDirective != null) {
                        throw new ResolveException(MessageKey.RESOLVE_DUPLICATE_DIRECTIVE, Arg.s("name", "measure"));
                    }
                    requirePermission(perm, catalog.byName("measure").permission(), "measure");
                    measureDirective = Measure.parse(a.positional().get(0));
                }
                case "commute" -> {
                    if (commuteDirective != null) {
                        throw new ResolveException(MessageKey.RESOLVE_DUPLICATE_DIRECTIVE, Arg.s("name", "commute"));
                    }
                    commuteDirective = Measure.parse(a.positional().get(0));
                }
                case "count" -> {
                    if (!countPermChecked) {
                        requirePermission(perm, catalog.byName("count").permission(), "count");
                        countPermChecked = true;
                    }
                    final String cited = a.positional().get(0);
                    if (!rules.exists(cited)) {
                        throw new ResolveException(MessageKey.RESOLVE_COUNT_UNKNOWN_RULE, Arg.s("rule", cited));
                    }
                    counts.add(cited);
                }
                case "remand" -> {
                    if (remand) {
                        throw new ResolveException(MessageKey.RESOLVE_DUPLICATE_DIRECTIVE, Arg.s("name", "remand"));
                    }
                    requirePermission(perm, catalog.byName("remand").permission(), "remand");
                    remand = true;
                }
                case "waive" -> {
                    requirePermission(perm, "minutiae.waive", "waive");
                    final String targetName = a.positional().get(0);
                    final AnnotationSpec waived = catalog.byName(targetName);
                    if (waived == null) {
                        throw new ResolveException(MessageKey.RESOLVE_WAIVE_UNKNOWN, Arg.s("name", targetName));
                    }
                    waivedMask |= waived.bit();
                }
                case "amend" -> {
                    if (amendId != 0L) {
                        throw new ResolveException(MessageKey.RESOLVE_DUPLICATE_DIRECTIVE, Arg.s("name", "amend"));
                    }
                    requirePermission(perm, "minutiae.amend", "amend");
                    amendId = Long.parseLong(a.positional().get(0));
                }
                default -> throw new ResolveException(MessageKey.RESOLVE_UNHANDLED_DIRECTIVE, Arg.s("name", a.name()));
            }
        }

        final Map<String, RawAnnotation> effective = mergeBehavioural(behavioural, layout, catalog);
        enforcePermissions(effective, catalog, perm);

        final long mask = maskOf(effective, catalog);
        final long expanded = matrix.implyClosure(mask);
        checkRelationships(matrix, catalog, expanded, waivedMask);

        // Amend short-circuit: the directive is mutually exclusive with a layout
        // selector and with measure-changing directives, and mutates an existing
        // sanction rather than resolving a fresh one.
        if (amendId != 0L) {
            if (layout != null) {
                throw new ResolveException(MessageKey.RESOLVE_AMEND_CONFLICT, Arg.s("with", "::layout"));
            }
            if (measureDirective != null) {
                throw new ResolveException(MessageKey.RESOLVE_AMEND_CONFLICT, Arg.s("with", "@measure"));
            }
            if (commuteDirective != null) {
                throw new ResolveException(MessageKey.RESOLVE_AMEND_CONFLICT, Arg.s("with", "@commute"));
            }
            if (remand) {
                throw new ResolveException(MessageKey.RESOLVE_AMEND_CONFLICT, Arg.s("with", "@remand"));
            }
            return resolveAmend(cmd, effective, counts, amendId);
        }

        final Measure measure = resolveMeasure(layout, measureDirective, commuteDirective, remand, perm);

        final DurationSpec tariffFor = durationParam(effective, "tariff");
        if (tariffFor != null && measure.temporal() != Measure.Temporal.TEMPORAL) {
            throw new ResolveException(MessageKey.RESOLVE_TARIFF_TEMPORAL, Arg.s("measure", measure.name()));
        }

        validateOverrides(cmd.overrides());
        final boolean durationOverridden = cmd.overrides().containsKey("duration");
        final DurationSpec duration = resolveDuration(measure, cmd.overrides(), layout);

        String primaryRule = layout != null ? layout.rule() : null;
        String[] extraCounts;
        if (primaryRule == null && !counts.isEmpty()) {
            primaryRule = counts.remove(0);
        }
        extraCounts = counts.toArray(new String[0]);

        final String reason = resolveReason(cmd.overrides(), effective, layout, primaryRule);
        final DurationSpec stayFor = durationParam(effective, "stay");
        final DurationSpec backdateFor = durationParam(effective, "backdate");
        final String attributedStaff = firstPositional(effective, "as");
        final String link = firstPositional(effective, "link");
        final boolean dryRun = effective.containsKey("dry-run");
        final boolean appealable = !"off".equals(firstPositional(effective, "appeal"));
        final DurationSpec decayFor = durationParam(effective, "decay");
        final int warnFirst = intParam(effective, "warn-first", 0);
        final boolean nowFlag = effective.containsKey("now");
        final boolean escalateFlag = effective.containsKey("escalate");
        final String[] notifyChannels = channelsOf(effective);

        final long weaveParent = weaveParentOf(effective);
        final DurationSpec probationFor = durationParam(effective, "probation");

        final DurationSpec suspendFor = durationParam(effective, "suspended");
        final DurationSpec reviewFor = durationParam(effective, "review");
        final DurationSpec expungeFor = durationParam(effective, "expunge");
        final String note = firstPositional(effective, "note");

        // A stay defers activation by time; a suspended sentence defers it
        // until recidivism. Combining the two yields no coherent activation
        // condition and is rejected.
        if (suspendFor != null && stayFor != null) {
            throw new ResolveException(MessageKey.RESOLVE_SUSPEND_STAY);
        }

        return new ResolvedSanction(cmd.target(), cmd.layoutKey(), measure, primaryRule, extraCounts,
                reason, duration, durationOverridden,
                layout != null ? layout.escalation() : new DurationSpec[0],
                stayFor, backdateFor, attributedStaff, link, expanded, effective, dryRun, remand,
                appealable, decayFor, warnFirst, nowFlag, escalateFlag, tariffFor, notifyChannels,
                0L, weaveParent, probationFor, suspendFor, reviewFor, expungeFor, note);
    }

    private ResolvedSanction resolveAmend(final ParsedCommand cmd,
                                          final Map<String, RawAnnotation> effective,
                                          final List<String> counts,
                                          final long amendId) {
        validateOverrides(cmd.overrides());
        final boolean durationOverridden = cmd.overrides().containsKey("duration");
        DurationSpec duration = null;
        if (durationOverridden) {
            try {
                duration = DurationSpec.parse(cmd.overrides().get("duration"));
            } catch (final IllegalArgumentException e) {
                throw new ResolveException(MessageKey.RESOLVE_INVALID_DURATION, Arg.s("error", e.getMessage()));
            }
        }

        String reason = cmd.overrides().get("reason");
        if (reason == null || reason.isEmpty()) {
            final RawAnnotation reasonAnn = effective.get("reason");
            if (reasonAnn != null && !reasonAnn.positional().isEmpty()) {
                reason = reasonAnn.positional().get(0);
            }
        }

        final String newRule = counts.isEmpty() ? null : counts.remove(0);
        final String[] newCounts = counts.toArray(new String[0]);

        return new ResolvedSanction(cmd.target(), null, null, newRule, newCounts,
                reason, duration, durationOverridden, new DurationSpec[0],
                null, null, null, null, 0L, Map.of(), false, false,
                true, null, 0, false, false, null, null,
                amendId, 0L, null, null, null, null, null);
    }

    private long weaveParentOf(final Map<String, RawAnnotation> effective) {
        final RawAnnotation a = effective.get("weave");
        if (a == null || a.positional().isEmpty()) {
            return 0L;
        }
        String v = a.positional().get(0).trim();
        if (v.startsWith("#")) {
            v = v.substring(1);
        }
        final long id;
        try {
            id = Long.parseLong(v);
        } catch (final NumberFormatException e) {
            throw new ResolveException(MessageKey.RESOLVE_WEAVE_FORMAT);
        }
        if (id <= 0L) {
            throw new ResolveException(MessageKey.RESOLVE_WEAVE_POSITIVE);
        }
        return id;
    }

    private Layout bindLayout(final String layoutKey) {
        if (layoutKey == null) {
            return null;
        }
        final Layout layout = layouts.get(layoutKey);
        if (layout == null) {
            throw new ResolveException(MessageKey.RESOLVE_UNKNOWN_LAYOUT, Arg.s("key", layoutKey));
        }
        return layout;
    }

    private Map<String, RawAnnotation> mergeBehavioural(final List<RawAnnotation> inline,
                                                        final Layout layout,
                                                        final AnnotationCatalog catalog) {
        final Map<String, RawAnnotation> effective = new LinkedHashMap<>(16);
        if (layout != null) {
            for (final RawAnnotation a : layout.annotations()) {
                effective.put(a.name(), a);
            }
        }
        for (final RawAnnotation a : inline) {
            final AnnotationSpec spec = catalog.byName(a.name());
            if (spec == null) {
                throw new ResolveException(MessageKey.RESOLVE_UNKNOWN_ANNOTATION, Arg.s("name", a.name()));
            }
            if (a.negated()) {
                effective.remove(a.name());
            } else {
                final String err = spec.validator().validate(a);
                if (err != null) {
                    throw new ResolveException("@" + a.name() + " " + err);
                }
                effective.put(a.name(), a);
            }
        }
        return effective;
    }

    private void enforcePermissions(final Map<String, RawAnnotation> effective,
                                    final AnnotationCatalog catalog,
                                    final Predicate<String> perm) {
        for (final String name : effective.keySet()) {
            final AnnotationSpec spec = catalog.byName(name);
            if (!perm.test(spec.permission())) {
                throw new ResolveException(MessageKey.RESOLVE_NO_PERMISSION, Arg.s("name", name));
            }
        }
    }

    private Measure resolveMeasure(final Layout layout,
                                   final Measure measureDirective,
                                   final Measure commuteDirective,
                                   final boolean remand,
                                   final Predicate<String> perm) {
        final Measure base = layout != null ? layout.measure() : null;

        if (remand) {
            if (base != null || measureDirective != null || commuteDirective != null) {
                throw new ResolveException(MessageKey.RESOLVE_REMAND_STANDALONE);
            }
            return Measure.QUARANTINE;
        }
        if (commuteDirective != null) {
            if (measureDirective != null) {
                throw new ResolveException(MessageKey.RESOLVE_MEASURE_COMMUTE_EXCLUSIVE);
            }
            if (base == null) {
                throw new ResolveException(MessageKey.RESOLVE_NOTHING_TO_COMMUTE);
            }
            gateCommute(base, commuteDirective, perm);
            return commuteDirective;
        }
        if (measureDirective != null) {
            if (layout != null) {
                throw new ResolveException(MessageKey.RESOLVE_LAYOUT_HAS_MEASURE);
            }
            return measureDirective;
        }
        if (base != null) {
            return base;
        }
        throw new ResolveException(MessageKey.RESOLVE_NO_MEASURE);
    }

    private void gateCommute(final Measure from, final Measure to, final Predicate<String> perm) {
        final int cmp = Integer.compare(to.severity(), from.severity());
        if (cmp < 0 && !perm.test("minutiae.commute.mitigate")) {
            throw new ResolveException(MessageKey.RESOLVE_COMMUTE_MITIGATE, Arg.s("from", from.name()), Arg.s("to", to.name()));
        }
        if (cmp > 0 && !perm.test("minutiae.commute.aggravate")) {
            throw new ResolveException(MessageKey.RESOLVE_COMMUTE_AGGRAVATE, Arg.s("from", from.name()), Arg.s("to", to.name()));
        }
    }

    private long maskOf(final Map<String, RawAnnotation> effective, final AnnotationCatalog catalog) {
        long mask = 0L;
        for (final String name : effective.keySet()) {
            mask |= catalog.byName(name).bit();
        }
        return mask;
    }

    private static int intParam(final Map<String, RawAnnotation> map, final String name, final int fallback) {
        final RawAnnotation a = map.get(name);
        if (a == null || a.positional().isEmpty()) {
            return fallback;
        }
        return Integer.parseInt(a.positional().get(0));
    }

    private static String[] channelsOf(final Map<String, RawAnnotation> map) {
        final RawAnnotation a = map.get("notify");
        if (a == null || a.positional().isEmpty()) {
            return null;
        }
        final String[] out = new String[a.positional().size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = a.positional().get(i).toLowerCase(java.util.Locale.ROOT);
        }
        return out;
    }

    private void checkRelationships(final AnnotationMatrix matrix,
                                    final AnnotationCatalog catalog,
                                    final long expanded,
                                    final long waivedMask) {
        final AnnotationMatrix.Conflict conflict = matrix.firstConflict(expanded);
        if (conflict != null) {
            throw new ResolveException(MessageKey.RESOLVE_CONFLICT, Arg.s("left", catalog.byOrdinal(conflict.left()).name()), Arg.s("right", catalog.byOrdinal(conflict.right()).name()));
        }
        final AnnotationMatrix.Missing missing = matrix.firstMissing(expanded | waivedMask);
        if (missing != null) {
            throw new ResolveException(MessageKey.RESOLVE_REQUIRES, Arg.s("requirer", catalog.byOrdinal(missing.requirer()).name()), Arg.s("required", catalog.byOrdinal(missing.required()).name()));
        }
    }

    private void validateOverrides(final Map<String, String> overrides) {
        for (final String key : overrides.keySet()) {
            if (!KNOWN_OVERRIDES.contains(key)) {
                throw new ResolveException(MessageKey.RESOLVE_UNKNOWN_OVERRIDE, Arg.s("key", key), Arg.s("allowed", KNOWN_OVERRIDES.toString()));
            }
        }
    }

    private DurationSpec resolveDuration(final Measure measure,
                                         final Map<String, String> overrides,
                                         final Layout layout) {
        final String override = overrides.get("duration");
        return switch (measure.temporal()) {
            case PERMANENT -> DurationSpec.PERMANENT;
            case INSTANTANEOUS -> {
                if (override != null) {
                    throw new ResolveException(MessageKey.RESOLVE_INSTANT_NO_DURATION, Arg.s("measure", measure.name()));
                }
                yield DurationSpec.ZERO;
            }
            case TEMPORAL -> {
                if (override != null) {
                    try {
                        yield DurationSpec.parse(override);
                    } catch (final IllegalArgumentException e) {
                        throw new ResolveException(MessageKey.RESOLVE_INVALID_DURATION, Arg.s("error", e.getMessage()));
                    }
                }
                if (layout != null && layout.duration() != null) {
                    yield layout.duration();
                }
                throw new ResolveException(MessageKey.RESOLVE_MEASURE_NEEDS_DURATION, Arg.s("measure", measure.name()));
            }
        };
    }

    private String resolveReason(final Map<String, String> overrides,
                                 final Map<String, RawAnnotation> effective,
                                 final Layout layout,
                                 final String rule) {
        final String override = overrides.get("reason");
        if (override != null && !override.isEmpty()) {
            return override;
        }
        final RawAnnotation reasonAnn = effective.get("reason");
        if (reasonAnn != null && !reasonAnn.positional().isEmpty()) {
            return reasonAnn.positional().get(0);
        }
        if (layout != null && layout.reason() != null) {
            return layout.reason();
        }
        return rule != null ? rules.describe(rule) : null;
    }

    private void requirePermission(final Predicate<String> perm, final String node, final String name) {
        if (!perm.test(node)) {
            throw new ResolveException(MessageKey.RESOLVE_NO_PERMISSION, Arg.s("name", name));
        }
    }

    private static DurationSpec durationParam(final Map<String, RawAnnotation> map, final String name) {
        final RawAnnotation a = map.get(name);
        if (a == null || a.positional().isEmpty()) {
            return null;
        }
        return DurationSpec.parse(a.positional().get(0));
    }

    private static String firstPositional(final Map<String, RawAnnotation> map, final String name) {
        final RawAnnotation a = map.get(name);
        return (a == null || a.positional().isEmpty()) ? null : a.positional().get(0);
    }
}