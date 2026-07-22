package org.synergyst.minutiae.lang.run;

import org.synergyst.minutiae.annotation.RawAnnotation;
import org.synergyst.minutiae.command.parse.ParsedCommand;
import org.synergyst.minutiae.engine.EventFacts;
import org.synergyst.minutiae.lang.eval.Value;
import org.synergyst.minutiae.time.DurationSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Materialises a sanction descriptor into the command surface consumed by the
 * shared resolver.
 *
 * <p>Routing every automatically produced sanction through the resolver
 * guarantees that it undergoes the identical relationship, temporal, and
 * permission checks applied to a manually typed command. The materialiser
 * therefore translates descriptor fields into the resolver's own vocabulary
 * rather than bypassing it:
 * <ul>
 *   <li>{@code cite} entries become {@code count} directives; the resolver
 *       promotes the first to the primary rule;</li>
 *   <li>{@code measure} becomes a {@code measure} directive;</li>
 *   <li>{@code Duration::Fixed} becomes a {@code duration} override;
 *       {@code Duration::Permanent} emits nothing, which the resolver treats
 *       as the measure's own temporal class demands;</li>
 *   <li>{@code Escalation::Steps} becomes the {@code escalate} directive; a
 *       command-surface sanction carries no ladder of its own, so the ladder
 *       contents are not transported;</li>
 *   <li>each annotation variant becomes its surface token;
 *       {@code Annotation::Reason} becomes the {@code reason} override;</li>
 *   <li>{@code Attribution::Staff} becomes an {@code as} directive;
 *       {@code Attribution::System} is signalled to the caller, which
 *       attributes the sanction to the configured system actor;</li>
 *   <li>{@code DryRun::Forced} is signalled to the caller, which combines it
 *       with the arming posture of the owning automaton.</li>
 * </ul>
 *
 * <p>The target token is the subject's display name when the descriptor
 * targets the triggering subject, because the command surface resolves
 * players by name; a descriptor targeting a different account falls back to
 * the canonical UUID string, which the resolver treats as an exact token.
 *
 * <p>The materialiser is stateless, performs no I/O, and never throws for a
 * descriptor that passed compilation: every field it reads is mandatory and
 * typed by construction.
 */
public final class SanctionMaterializer {

    /**
     * A materialised sanction ready for resolution.
     *
     * @param command           the command-surface structure
     * @param systemAttribution whether attribution falls to the system actor
     * @param forcedDryRun      whether the descriptor forces dry-run
     */
    public record Materialized(ParsedCommand command,
                               boolean systemAttribution,
                               boolean forcedDryRun) {
    }

    private SanctionMaterializer() {
    }

    /**
     * Materialises one sanction descriptor.
     *
     * @param sanction the descriptor record produced by a verdict
     * @param facts    the facts of the completing event, used for target
     *                 name resolution
     * @return the materialised sanction
     */
    public static Materialized materialize(final Value.RecordV sanction,
                                           final EventFacts facts) {
        final List<RawAnnotation> annotations = new ArrayList<>(8);
        final Map<String, String> overrides = new LinkedHashMap<>(2);

        // Citations: every cited rule becomes a count directive in order.
        for (final Value cited : ((Value.ListV) sanction.field("cite")).items()) {
            annotations.add(directive("count", ((Value.RuleIdV) cited).id()));
        }

        // Measure: the constructor name equals the runtime measure name.
        final Value.VariantV measure = (Value.VariantV) sanction.field("measure");
        annotations.add(directive("measure", measure.ctor()));

        // Duration: only a fixed finite span is transported explicitly.
        final Value.VariantV duration = (Value.VariantV) sanction.field("duration");
        if (duration.ctor().equals("Fixed")) {
            final DurationSpec d = ((Value.DurV) duration.field(0)).value();
            overrides.put("duration", d.format());
        }

        // Escalation: presence of a ladder engages the escalate directive.
        final Value.VariantV escalation = (Value.VariantV) sanction.field("escalation");
        if (escalation.ctor().equals("Steps")) {
            annotations.add(flag("escalate"));
        }

        boolean systemAttribution = false;
        final Value.VariantV attribution = (Value.VariantV) sanction.field("attribution");
        if (attribution.ctor().equals("Staff")) {
            annotations.add(directive("as", ((Value.TextV) attribution.field(0)).value()));
        } else {
            systemAttribution = true;
        }

        final Value.VariantV dryRun = (Value.VariantV) sanction.field("dry_run");
        final boolean forced = dryRun.ctor().equals("Forced");

        for (final Value a : ((Value.ListV) sanction.field("annotations")).items()) {
            appendAnnotation((Value.VariantV) a, annotations, overrides);
        }

        final String target = targetToken(sanction, facts);
        return new Materialized(
                new ParsedCommand(target, null, annotations, overrides),
                systemAttribution, forced);
    }

    private static void appendAnnotation(final Value.VariantV a,
                                         final List<RawAnnotation> out,
                                         final Map<String, String> overrides) {
        switch (a.ctor()) {
            case "Notify" -> out.add(directive("notify", text(a, 0)));
            case "Evidence" -> out.add(flag("evidence"));
            case "Escalate" -> out.add(flag("escalate"));
            case "Silent" -> out.add(flag("silent"));
            case "Shadow" -> out.add(flag("shadow"));
            case "Ghost" -> out.add(flag("ghost"));
            case "Rubberband" -> out.add(flag("rubberband"));
            case "Transcript" -> out.add(flag("transcript"));
            case "Reason" -> overrides.put("reason", text(a, 0));
            case "Link" -> out.add(directive("link", text(a, 0)));
            case "WarnFirst" -> out.add(directive("warn-first",
                    Long.toString(((Value.IntV) a.field(0)).value())));
            case "Decay" -> out.add(directive("decay", dur(a)));
            case "Stay" -> out.add(directive("stay", dur(a)));
            case "Tariff" -> out.add(directive("tariff", dur(a)));
            case "Probation" -> out.add(directive("probation", dur(a)));
            default -> {
                // The annotation sum is closed; an unknown constructor cannot
                // reach a verdict that passed compilation.
            }
        }
    }

    private static String targetToken(final Value.RecordV sanction, final EventFacts facts) {
        final String uuid = ((Value.TextV) sanction.field("target")).value();
        final String subjectUuid = facts.subject() == null ? "" : facts.subject().toString();
        if (uuid.equals(subjectUuid) && facts.subjectName() != null
                && !facts.subjectName().isEmpty()) {
            return facts.subjectName();
        }
        return uuid;
    }

    private static String text(final Value.VariantV v, final int index) {
        return ((Value.TextV) v.field(index)).value();
    }

    private static String dur(final Value.VariantV v) {
        return ((Value.DurV) v.field(0)).value().format();
    }

    private static RawAnnotation flag(final String name) {
        return new RawAnnotation(false, name, List.of(), Map.of());
    }

    private static RawAnnotation directive(final String name, final String value) {
        return new RawAnnotation(false, name, List.of(value), Map.of());
    }
}