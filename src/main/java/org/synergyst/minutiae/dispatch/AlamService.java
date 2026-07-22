package org.synergyst.minutiae.dispatch;

import org.synergyst.minutiae.lang.diag.Diagnostic;
import org.synergyst.minutiae.lang.diag.Severity;
import org.synergyst.minutiae.lang.plan.Planner;
import org.synergyst.minutiae.lang.plan.RulePlan;
import org.synergyst.minutiae.lang.plan.UnitPlan;
import org.synergyst.minutiae.layout.LayoutDefinition;
import org.synergyst.minutiae.lifecycle.LifecycleComponent;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.rule.RuleRegistry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loader and publisher of compiled definition units.
 *
 * <p>On boot the service scans the {@code mtx} subdirectory of the plugin
 * data folder for {@code *.mtx} sources and plans each through the full
 * pipeline: lexing, parsing, elaboration, compile-time evaluation,
 * verification against the rule registry, and lowering. Every diagnostic is
 * logged with its source, position, and stable code. A source that fails any
 * phase is rejected whole - the planner never yields a partial plan - while
 * sibling sources load normally.
 *
 * <p>Two products are published and thereafter immutable until the next boot:
 * the accepted unit plans, consumed by the dispatch engine, and the aggregate
 * stamped layout definitions, consumed by the layout registry through its
 * stamped-definition supplier. Layout keys are deduplicated across units at
 * publication: the first producer wins and every later collision is reported
 * and dropped, so key ownership is deterministic in file-name order.
 *
 * <p>The service performs file I/O during {@link #boot()} only, on the boot
 * thread, before tick processing begins. It holds no platform references and
 * is safe for concurrent read after publication.
 */
public final class AlamService implements LifecycleComponent {

    private static final String DIRECTORY = "alam";
    private static final String EXTENSION = ".alam";

    private final KernelLogger log;
    private final File dataFolder;
    private final RuleRegistry rules;

    private volatile List<UnitPlan> plans = Collections.emptyList();
    private volatile List<LayoutDefinition> stampedLayouts = Collections.emptyList();

    public AlamService(final KernelLogger log, final File dataFolder, final RuleRegistry rules) {
        this.log = log;
        this.dataFolder = dataFolder;
        this.rules = rules;
    }

    @Override
    public String tag() {
        return "alam";
    }

    @Override
    public void boot() {
        final File dir = new File(dataFolder, DIRECTORY);
        if (!dir.isDirectory()) {
            if (dir.mkdirs()) {
                log.trace("alam", "created empty definitions directory '%s'", DIRECTORY);
            }
            log.info("alam", "no definitions present; language layer idle");
            clear();
            return;
        }
        final File[] files = dir.listFiles((d, n) -> n.endsWith(EXTENSION));
        if (files == null || files.length == 0) {
            log.info("alam", "no '*%s' definitions in '%s'; language layer idle",
                    EXTENSION, DIRECTORY);
            clear();
            return;
        }
        // Deterministic load order fixes layout-key ownership across units.
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));

        final List<UnitPlan> accepted = new ArrayList<>(files.length);
        final List<LayoutDefinition> layouts = new ArrayList<>();
        final Set<String> layoutKeys = new HashSet<>();
        int rejected = 0;
        int warnings = 0;

        for (final File file : files) {
            final String source;
            try {
                source = Files.readString(file.toPath());
            } catch (final IOException e) {
                log.error("alam", e, "failed to read '%s'; skipped", file.getName());
                rejected++;
                continue;
            }
            final Planner.Result result = Planner.plan(file.getName(), source, rules::exists);
            for (final Diagnostic d : result.diagnostics()) {
                if (d.severity() == Severity.ERROR) {
                    log.error("alam", "%s: %s", file.getName(), d.render());
                } else {
                    warnings++;
                    log.warn("alam", "%s: %s", file.getName(), d.render());
                }
            }
            if (!result.ok()) {
                rejected++;
                log.error("alam", "unit '%s' rejected; no rule of this unit is armed",
                        file.getName());
                continue;
            }
            accepted.add(result.plan());
            for (final LayoutDefinition def : result.plan().layouts()) {
                if (!layoutKeys.add(def.key())) {
                    log.warn("alam", "%s: stamped layout '%s' dropped: key already stamped"
                            + " by an earlier unit", file.getName(), def.key());
                    continue;
                }
                layouts.add(def);
            }
        }

        this.plans = List.copyOf(accepted);
        this.stampedLayouts = List.copyOf(layouts);

        int ruleCount = 0;
        int automatonCount = 0;
        for (final UnitPlan p : accepted) {
            ruleCount += p.ruleCount();
            automatonCount += p.automata().size();
        }
        log.info("alam", "planned %d unit(s): %d accepted, %d rejected, %d automaton(a),"
                        + " %d rule plan(s), %d layout(s) stamped, %d warning(s)",
                files.length, accepted.size(), rejected, automatonCount,
                ruleCount, layouts.size(), warnings);
    }

    @Override
    public void shutdown() {
        clear();
    }

    /** Returns the accepted unit plans, in file-name order. */
    public List<UnitPlan> plans() {
        return plans;
    }

    /** Returns the aggregate stamped layout definitions, deduplicated by key. */
    public List<LayoutDefinition> stampedLayouts() {
        return stampedLayouts;
    }

    /** Returns the total number of rule plans across accepted units. */
    public int ruleCount() {
        int n = 0;
        for (final UnitPlan p : plans) {
            n += p.ruleCount();
        }
        return n;
    }

    /** Enumerates every armed rule across accepted units, per automaton. */
    public List<ArmedRule> armedRules() {
        final List<ArmedRule> out = new ArrayList<>();
        for (final UnitPlan unit : plans) {
            for (final Map.Entry<String, List<RulePlan>> e : unit.automata().entrySet()) {
                for (final RulePlan plan : e.getValue()) {
                    out.add(new ArmedRule(e.getKey(), plan, unit.interp()));
                }
            }
        }
        return List.copyOf(out);
    }

    private void clear() {
        this.plans = Collections.emptyList();
        this.stampedLayouts = Collections.emptyList();
    }
}