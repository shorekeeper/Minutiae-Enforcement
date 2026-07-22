package org.synergyst.minutiae.command;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.synergyst.minutiae.annotation.AnnotationCatalog;
import org.synergyst.minutiae.annotation.AnnotationRegistry;
import org.synergyst.minutiae.annotation.AnnotationSpec;
import org.synergyst.minutiae.annotation.ParamHint;
import org.synergyst.minutiae.annotation.RawAnnotation;
import org.synergyst.minutiae.command.dsl.CompleteCtx;
import org.synergyst.minutiae.command.parse.CommandParseException;
import org.synergyst.minutiae.command.parse.CommandTokenizer;
import org.synergyst.minutiae.layout.Layout;
import org.synergyst.minutiae.layout.LayoutRegistry;
import org.synergyst.minutiae.measure.Measure;
import org.synergyst.minutiae.rule.RuleRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Context-sensitive completer for the {@code /enforce <spec>} grammar.
 *
 * <p>The completer reconstructs the partial command state from the tokens
 * already typed and suggests only what is grammatically and semantically
 * admissible next. The state comprises: whether a target token is present,
 * the bound layout key, the annotation names already applied, the override
 * keys already applied, and the measure directive if one was typed. State
 * reconstruction is lenient: a token stream that fails to tokenise (an
 * unterminated quote mid-typing) degrades to the empty state rather than
 * suppressing completion.
 *
 * <p>Dispatch on the trailing token:
 * <ul>
 *   <li>{@code ::} prefix - invocable layout keys, offered only while no
 *       layout and no measure directive is present;</li>
 *   <li>{@code @name(} or {@code !@name(} - parameter values selected by the
 *       annotation's {@link ParamHint}; list-valued hints complete the
 *       fragment after the last top-level comma;</li>
 *   <li>{@code @} or {@code !@} - annotation names not already present; the
 *       negated form is narrowed to the bound layout's own annotations, since
 *       negation removes layout-supplied tokens only;</li>
 *   <li>{@code key=} - override values; duration exemplars for
 *       {@code duration=}, nothing for free-text keys;</li>
 *   <li>plain token, no target yet - online player names;</li>
 *   <li>plain token, target present - a curated next-step menu derived from
 *       what the resolver still requires: layout keys and measure directives
 *       while no measure source exists, duration exemplars while a temporal
 *       measure lacks a duration, and the common modifiers.</li>
 * </ul>
 *
 * <p>The framework applies case-insensitive prefix filtering and the client
 * sorts suggestions lexicographically; the completer controls the candidate
 * set only, not its presentation order.
 *
 * <p>The completer is stateless between invocations, performs no I/O, and
 * reads only immutable registries, the recent-identifier ring, and the
 * online-player set; it is safe on the suggestion path at keystroke rate.
 */
public final class SpecCompleter {

    /** Duration exemplars offered inside annotation parameters. */
    private static final String[] PARAM_DURATIONS = {"30m", "1h", "6h", "1d", "7d", "30d"};

    /** Duration exemplars offered for the duration override, permanent included. */
    private static final String[] OVERRIDE_DURATIONS =
            {"30m", "1h", "6h", "1d", "7d", "30d", "permanent"};

    /** Positive-integer exemplars for quota-style parameters. */
    private static final String[] SMALL_INTS = {"1", "2", "3"};

    /** Common modifier annotations offered in the next-step menu when absent. */
    private static final String[] MENU_FLAGS = {"dry-run", "silent"};

    private final LayoutRegistry layouts;
    private final AnnotationRegistry annotations;
    private final RuleRegistry rules;
    private final RecentIds recents;
    private final Supplier<Collection<String>> channelNames;

    public SpecCompleter(final LayoutRegistry layouts,
                         final AnnotationRegistry annotations,
                         final RuleRegistry rules,
                         final RecentIds recents,
                         final Supplier<Collection<String>> channelNames) {
        this.layouts = layouts;
        this.annotations = annotations;
        this.rules = rules;
        this.recents = recents;
        this.channelNames = channelNames;
    }

    /**
     * Reconstructed state of a partially typed specification.
     *
     * @param hasTarget        whether a target token is present
     * @param layoutKey        the bound layout key, or null
     * @param annotationNames  annotation names already applied
     * @param overrideKeys     override keys already applied
     * @param measureDirective the measure from an {@code @measure(...)} token,
     *                         or null
     */
    private record SpecState(boolean hasTarget,
                             String layoutKey,
                             Set<String> annotationNames,
                             Set<String> overrideKeys,
                             Measure measureDirective) {
    }

    /**
     * Computes candidates for the trailing token of a specification.
     *
     * @param ctx the completion context
     * @return candidate replacement tokens
     */
    public List<String> complete(final CompleteCtx ctx) {
        final String p = ctx.partial();
        final String prior = ctx.full().substring(0, ctx.full().length() - p.length());
        final SpecState st = stateOf(prior);

        if (p.startsWith("::")) {
            return layoutCandidates(st);
        }
        if (p.startsWith("@") || p.startsWith("!@")) {
            final int paren = p.indexOf('(');
            if (paren >= 0) {
                return paramCandidates(p, paren);
            }
            return annotationCandidates(p.startsWith("!@") ? "!@" : "@", st);
        }
        final int eq = topLevelEquals(p);
        if (eq > 0) {
            return overrideCandidates(p.substring(0, eq));
        }
        if (!st.hasTarget()) {
            return onlineNames();
        }
        return nextStepMenu(st);
    }

    // ------------------------------------------------------------------
    // State reconstruction
    // ------------------------------------------------------------------

    private SpecState stateOf(final String prior) {
        List<String> tokens;
        try {
            tokens = CommandTokenizer.tokenize(prior);
        } catch (final CommandParseException midTyping) {
            tokens = List.of();
        }
        boolean hasTarget = false;
        String layoutKey = null;
        Measure measureDirective = null;
        final Set<String> names = new HashSet<>(8);
        final Set<String> overrides = new HashSet<>(4);

        for (final String token : tokens) {
            if (token.startsWith("::")) {
                layoutKey = token.substring(2);
            } else if (token.startsWith("@") || token.startsWith("!@")) {
                final String bare = bareName(token);
                names.add(bare);
                if (bare.equals("measure")) {
                    measureDirective = measureOf(token);
                }
            } else {
                final int eq = topLevelEquals(token);
                if (eq > 0) {
                    overrides.add(token.substring(0, eq).trim());
                } else {
                    hasTarget = true;
                }
            }
        }
        return new SpecState(hasTarget, layoutKey, names, overrides, measureDirective);
    }

    private static String bareName(final String token) {
        final int start = token.startsWith("!@") ? 2 : 1;
        final int paren = token.indexOf('(');
        return paren < 0 ? token.substring(start) : token.substring(start, paren);
    }

    private static Measure measureOf(final String token) {
        final int open = token.indexOf('(');
        final int close = token.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return null;
        }
        try {
            return Measure.parse(token.substring(open + 1, close));
        } catch (final IllegalArgumentException unknown) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Candidate producers
    // ------------------------------------------------------------------

    private List<String> layoutCandidates(final SpecState st) {
        if (st.layoutKey() != null || st.measureDirective() != null) {
            return List.of();
        }
        final List<String> out = new ArrayList<>(layouts.size());
        for (final String key : layouts.keys()) {
            out.add("::" + key);
        }
        return out;
    }

    private List<String> annotationCandidates(final String sigil, final SpecState st) {
        final AnnotationCatalog catalog = annotations.catalog();
        final List<String> out = new ArrayList<>(catalog.size());
        if (sigil.equals("!@")) {
            // Negation removes layout-supplied annotations; only the bound
            // layout's own names are meaningful targets.
            final Layout layout = st.layoutKey() != null ? layouts.get(st.layoutKey()) : null;
            if (layout == null) {
                return out;
            }
            final Set<String> seen = new HashSet<>(8);
            for (final RawAnnotation a : layout.annotations()) {
                if (seen.add(a.name())) {
                    out.add("!@" + a.name());
                }
            }
            return out;
        }
        for (final AnnotationSpec spec : catalog.all()) {
            if (!st.annotationNames().contains(spec.name())) {
                out.add("@" + spec.name());
            }
        }
        return out;
    }

    private List<String> paramCandidates(final String p, final int paren) {
        final String prefix = p.substring(0, paren);
        final String bare = prefix.startsWith("!@") ? prefix.substring(2) : prefix.substring(1);
        final AnnotationSpec spec = annotations.catalog().byName(bare);
        if (spec == null) {
            return List.of();
        }
        // The base is everything up to the fragment under the cursor: the text
        // through the last top-level comma (or the opening parenthesis) plus
        // any whitespace the user typed after it, so a candidate formed as
        // base + value remains a prefix-extension of the partial.
        final String base = paramBase(p);
        return switch (spec.hint()) {
            case MEASURE -> closed(base, measureNames());
            case DURATION -> closed(base, PARAM_DURATIONS);
            case POSITIVE_INT -> closed(base, SMALL_INTS);
            case RULE_ID -> closed(base, rules.identifiers());
            case ANNOTATION_NAME -> closed(base, annotationNames());
            case CHANNELS -> closed(base, channelNames.get().toArray(new String[0]));
            case TOGGLE -> closed(base, new String[]{"on", "off"});
            case EVIDENCE -> closed(base, new String[]{"required"});
            case STAFF_NAME -> closed(base, onlineNames().toArray(new String[0]));
            case SANCTION_ID -> closed(base, recents.sanctionIds().toArray(new String[0]));
            case NONE, TEXT, REFERENCE -> List.of();
        };
    }

    private static String paramBase(final String p) {
        int fragStart = Math.max(p.indexOf('('), lastTopLevelComma(p)) + 1;
        while (fragStart < p.length() && p.charAt(fragStart) == ' ') {
            fragStart++;
        }
        return p.substring(0, fragStart);
    }

    private static int lastTopLevelComma(final String p) {
        boolean quoted = false;
        int last = -1;
        for (int i = 0, n = p.length(); i < n; i++) {
            final char c = p.charAt(i);
            if (c == '"' && (i == 0 || p.charAt(i - 1) != '\\')) {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                last = i;
            }
        }
        return last;
    }

    private static List<String> closed(final String base, final String[] values) {
        final List<String> out = new ArrayList<>(values.length);
        for (final String v : values) {
            out.add(base + v + ")");
        }
        return out;
    }

    private static List<String> closed(final String base, final List<String> values) {
        return closed(base, values.toArray(new String[0]));
    }

    private List<String> overrideCandidates(final String key) {
        if (!key.trim().equals("duration")) {
            return List.of();
        }
        final List<String> out = new ArrayList<>(OVERRIDE_DURATIONS.length);
        for (final String d : OVERRIDE_DURATIONS) {
            out.add("duration=" + d);
        }
        return out;
    }

    private List<String> nextStepMenu(final SpecState st) {
        final List<String> out = new ArrayList<>(24);
        final Layout layout = st.layoutKey() != null ? layouts.get(st.layoutKey()) : null;
        Measure measure = st.measureDirective();
        if (measure == null && layout != null) {
            measure = layout.measure();
        }

        if (layout == null && st.measureDirective() == null
                && !st.annotationNames().contains("remand")
                && !st.annotationNames().contains("amend")) {
            for (final String key : layouts.keys()) {
                out.add("::" + key);
            }
            for (final Measure m : Measure.values()) {
                out.add("@measure(" + m.name() + ")");
            }
            out.add("@remand");
        }
        if (layout != null && !st.annotationNames().contains("commute")) {
            for (final Measure m : Measure.values()) {
                out.add("@commute(" + m.name() + ")");
            }
        }
        if (measure != null && measure.temporal() == Measure.Temporal.TEMPORAL
                && !st.overrideKeys().contains("duration")
                && (layout == null || layout.duration() == null)) {
            for (final String d : OVERRIDE_DURATIONS) {
                out.add("duration=" + d);
            }
        }
        if (!st.overrideKeys().contains("reason")
                && !st.annotationNames().contains("reason")) {
            out.add("reason=");
        }
        for (final String flag : MENU_FLAGS) {
            if (!st.annotationNames().contains(flag)) {
                out.add("@" + flag);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Small sources
    // ------------------------------------------------------------------

    private static String[] measureNames() {
        final Measure[] measures = Measure.values();
        final String[] out = new String[measures.length];
        for (int i = 0; i < measures.length; i++) {
            out[i] = measures[i].name();
        }
        return out;
    }

    private List<String> annotationNames() {
        final AnnotationSpec[] all = annotations.catalog().all();
        final List<String> out = new ArrayList<>(all.length);
        for (final AnnotationSpec spec : all) {
            out.add(spec.name());
        }
        return out;
    }

    private static List<String> onlineNames() {
        final List<String> out = new ArrayList<>(16);
        for (final Player player : Bukkit.getOnlinePlayers()) {
            out.add(player.getName());
        }
        return out;
    }

    /**
     * Locates the first top-level equals sign: outside double quotes and
     * outside parentheses. Mirrors the classification used by the command
     * parser so completion and parsing agree on what constitutes an override.
     *
     * @param s the token text
     * @return the index of the equals sign, or {@code -1}
     */
    private static int topLevelEquals(final String s) {
        boolean quoted = false;
        int depth = 0;
        for (int i = 0, n = s.length(); i < n; i++) {
            final char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
            } else if (c == '=' && !quoted && depth == 0) {
                return i;
            }
        }
        return -1;
    }
}