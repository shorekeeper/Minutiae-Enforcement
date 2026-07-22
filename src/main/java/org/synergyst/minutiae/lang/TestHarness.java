package org.synergyst.minutiae.lang;

import org.synergyst.minutiae.engine.GuardEnvironment;
import org.synergyst.minutiae.lang.diag.Diagnostic;
import org.synergyst.minutiae.lang.plan.Planner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Shared fixtures of the language test suite.
 *
 * <p>Provides source-text builders for the verbose mandatory-field literals,
 * a deterministic in-memory guard environment, and diagnostic-code
 * extraction helpers used by every phase test.
 */
public final class TestHarness {

    /** A rule-registry predicate accepting the canonical test identifiers. */
    public static final Predicate<String> RULES =
            Set.of("P.1.1", "P.2.1", "P.3.2")::contains;

    private TestHarness() {
    }

    /** Compiles a source through lexing, parsing, elaboration, evaluation. */
    public static CompiledUnit compile(final String src) {
        return Compiler.compile("test.alam", src);
    }

    /** Plans a source through every phase including verification. */
    public static Planner.Result plan(final String src, final Predicate<String> rules) {
        return Planner.plan("test.alam", src, rules);
    }

    /** Extracts the stable codes of a diagnostic list, in report order. */
    public static List<String> codes(final List<Diagnostic> diagnostics) {
        final List<String> out = new ArrayList<>(diagnostics.size());
        for (final Diagnostic d : diagnostics) {
            out.add(d.code());
        }
        return out;
    }

    /** Renders a total sanction literal with the given measure and duration. */
    public static String sanction(final String measure, final String durationTerm) {
        return """
                Sanction {
                    .target      = e.subject,
                    .cite        = { P.2.1 },
                    .measure     = %s,
                    .duration    = %s,
                    .escalation  = Escalation::None,
                    .annotations = { Annotation::Notify("staff") },
                    .attribution = Attribution::System,
                    .dry_run     = DryRun::InheritSafety,
                }""".formatted(measure, durationTerm);
    }

    /** Renders a total layout literal with injectable field overrides. */
    public static String layout(final String key, final String measure,
                                final String durationTerm, final String escalation,
                                final String annotations) {
        return """
                Layout {
                    .key         = %s,
                    .rule        = P.1.1,
                    .reason      = "reason",
                    .measure     = %s,
                    .duration    = %s,
                    .escalation  = %s,
                    .annotations = %s,
                }""".formatted(key, measure, durationTerm, escalation, annotations);
    }

    /** Deterministic in-memory guard environment. */
    public static final class FakeEnv implements GuardEnvironment {

        public final Map<String, Long> precedent = new HashMap<>();
        public final Map<UUID, Double> scores = new HashMap<>();
        public final Set<UUID> online = new java.util.HashSet<>();
        public long clock = 1_000_000L;

        @Override
        public long precedent(final UUID subject, final String rule) {
            return precedent.getOrDefault(subject + "|" + rule, 0L);
        }

        @Override
        public double fingerprintScore(final UUID subject) {
            return scores.getOrDefault(subject, 0.0d);
        }

        @Override
        public boolean isOnline(final UUID subject) {
            return online.contains(subject);
        }

        @Override
        public long now() {
            return clock;
        }
    }
}