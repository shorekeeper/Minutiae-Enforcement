package org.synergyst.minutiae.execute;

import org.synergyst.minutiae.annotation.AnnotationCatalog;
import org.synergyst.minutiae.annotation.AnnotationRegistry;
import org.synergyst.minutiae.annotation.AnnotationSpec;
import org.synergyst.minutiae.annotation.AnnotationSyntaxException;
import org.synergyst.minutiae.annotation.AnnotationTokenParser;
import org.synergyst.minutiae.annotation.RawAnnotation;
import org.synergyst.minutiae.command.parse.CommandTokenizer;
import org.synergyst.minutiae.message.Arg;
import org.synergyst.minutiae.message.MessageKey;
import org.synergyst.minutiae.resolve.ResolveException;
import org.synergyst.minutiae.time.DurationSpec;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Resolves the lift command's token remainder into a {@link ResolvedLift}.
 *
 * <p>The remainder shares the tokeniser of the issuance grammar: tokens
 * beginning with {@code @} are annotations, every other token contributes to
 * the reason, joined in source order by single spaces. Negation is
 * meaningless on a lift and is rejected.
 *
 * <p>The admissible annotation set is a closed subset of the catalogue plus
 * one lift-only directive:
 * <ul>
 *   <li>{@code @silent} - suppress the public announcement;</li>
 *   <li>{@code @dry-run} - preview without effect;</li>
 *   <li>{@code @note(text)} - attach an internal case note;</li>
 *   <li>{@code @notify(channel...)} - route a lift notification;</li>
 *   <li>{@code @probation(duration)} - open a probation window on the lifted
 *       record; rejected for a vacate, whose record leaves precedent and
 *       cannot carry a visible window;</li>
 *   <li>{@code @cascade} - extend the lift over the joinder chain; a
 *       lift-only directive absent from the catalogue, validated and
 *       permission-gated locally.</li>
 * </ul>
 * Catalogue-backed annotations reuse their catalogue validators and
 * permission nodes, so the parameter contract and the grant surface stay in
 * one place.
 *
 * <p>Kind selection is permission-gated here as well: a vacate is covered by
 * the base lift permission, while pardon and time-served require their
 * dedicated nodes, since they change what the record continues to mean rather
 * than merely ending its effect.
 *
 * <p>The resolver performs no I/O, holds only the immutable annotation
 * registry, and is safe for concurrent use.
 */
public final class LiftResolver {

    /** Catalogue annotations admissible on a lift. */
    private static final Set<String> LIFT_SCOPE =
            Set.of("silent", "dry-run", "note", "notify", "probation");

    /** Lift-only cascade directive name. */
    private static final String CASCADE = "cascade";

    /** Permission node gating the cascade directive. */
    public static final String PERMISSION_CASCADE = "minutiae.lift.cascade";

    /** Permission node gating the pardon kind. */
    public static final String PERMISSION_PARDON = "minutiae.lift.pardon";

    /** Permission node gating the time-served kind. */
    public static final String PERMISSION_SERVED = "minutiae.lift.served";

    private final AnnotationRegistry annotations;

    public LiftResolver(final AnnotationRegistry annotations) {
        this.annotations = annotations;
    }

    /**
     * Resolves a lift request.
     *
     * @param kind      the kind selected by the command literal
     * @param remainder the raw token remainder, possibly empty
     * @param perm      predicate indicating whether the sender holds a
     *                  permission
     * @return the resolved lift
     * @throws ResolveException on any semantic violation
     */
    public ResolvedLift resolve(final LiftKind kind, final String remainder,
                                final Predicate<String> perm) {
        gateKind(kind, perm);

        boolean silent = false;
        boolean dryRun = false;
        boolean cascade = false;
        String note = null;
        DurationSpec probationFor = null;
        String[] notifyChannels = null;
        final StringBuilder reason = new StringBuilder(48);

        final List<String> tokens;
        try {
            tokens = CommandTokenizer.tokenize(remainder == null ? "" : remainder);
        } catch (final org.synergyst.minutiae.command.parse.CommandParseException e) {
            if (e.hasKey()) {
                throw new ResolveException(e.key(), e.args());
            }
            throw new ResolveException(e.getMessage());
        }

        final AnnotationCatalog catalog = annotations.catalog();
        for (final String token : tokens) {
            if (!token.startsWith("@") && !token.startsWith("!@")) {
                if (reason.length() > 0) {
                    reason.append(' ');
                }
                reason.append(unquote(token));
                continue;
            }
            final RawAnnotation a;
            try {
                a = AnnotationTokenParser.parse(token);
            } catch (final AnnotationSyntaxException e) {
                throw new ResolveException(MessageKey.PARSE_MALFORMED_ANNOTATION,
                        Arg.s("token", token), Arg.s("error", e.getMessage()));
            }
            if (a.negated()) {
                throw new ResolveException(MessageKey.RESOLVE_DIRECTIVE_NEGATED,
                        Arg.s("name", a.name()));
            }
            if (a.name().equals(CASCADE)) {
                if (a.hasParams()) {
                    throw new ResolveException("@" + CASCADE + " takes no parameters");
                }
                requirePermission(perm, PERMISSION_CASCADE, CASCADE);
                cascade = true;
                continue;
            }
            if (!LIFT_SCOPE.contains(a.name())) {
                throw new ResolveException(MessageKey.LIFT_UNKNOWN_TOKEN,
                        Arg.s("name", a.name()));
            }
            final AnnotationSpec spec = catalog.byName(a.name());
            final String paramError = spec.validator().validate(a);
            if (paramError != null) {
                throw new ResolveException("@" + a.name() + " " + paramError);
            }
            requirePermission(perm, spec.permission(), a.name());

            switch (a.name()) {
                case "silent" -> silent = true;
                case "dry-run" -> dryRun = true;
                case "note" -> note = a.positional().get(0);
                case "probation" -> probationFor = DurationSpec.parse(a.positional().get(0));
                case "notify" -> {
                    final String[] channels = new String[a.positional().size()];
                    for (int i = 0; i < channels.length; i++) {
                        channels[i] = a.positional().get(i).toLowerCase(Locale.ROOT);
                    }
                    notifyChannels = channels;
                }
                default -> throw new ResolveException(MessageKey.RESOLVE_UNHANDLED_DIRECTIVE,
                        Arg.s("name", a.name()));
            }
        }

        if (probationFor != null && kind == LiftKind.VACATE) {
            throw new ResolveException(MessageKey.LIFT_PROBATION_VACATE);
        }

        return new ResolvedLift(kind, reason.length() == 0 ? null : reason.toString(),
                silent, dryRun, cascade, note, probationFor, notifyChannels);
    }

    private void gateKind(final LiftKind kind, final Predicate<String> perm) {
        final String node = switch (kind) {
            case VACATE -> null;
            case PARDON -> PERMISSION_PARDON;
            case TIME_SERVED -> PERMISSION_SERVED;
        };
        if (node != null && !perm.test(node)) {
            throw new ResolveException(MessageKey.LIFT_DENY_KIND,
                    Arg.key("kind", MessageKey.liftKindKey(kind)));
        }
    }

    private static void requirePermission(final Predicate<String> perm, final String node,
                                          final String name) {
        if (!perm.test(node)) {
            throw new ResolveException(MessageKey.RESOLVE_NO_PERMISSION, Arg.s("name", name));
        }
    }

    private static String unquote(final String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return s;
    }
}