package org.synergyst.minutiae.layout;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.synergyst.minutiae.annotation.AnnotationTokenParser;
import org.synergyst.minutiae.annotation.RawAnnotation;
import org.synergyst.minutiae.lifecycle.LifecycleComponent;
import org.synergyst.minutiae.lifecycle.Reloadable;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.rule.RuleRegistry;
import org.synergyst.minutiae.time.DurationSpec;
import org.synergyst.minutiae.annotation.AnnotationRegistry;
import org.synergyst.minutiae.measure.Measure;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Authoritative registry of invocable layouts.
 *
 * <p>Boot proceeds in phases. First, raw definitions are read from the
 * configured file; a definition whose scalar or annotation fields are malformed
 * is skipped with a diagnostic and does not abort the load. Second, externally
 * supplied stamped definitions are ingested alongside the file-authored ones; a
 * stamped definition whose key collides with a file-authored key is reported and
 * discarded, the file remaining authoritative. Third, the combined definitions
 * are flattened through {@link LayoutResolver}, which isolates inheritance
 * failures. Fourth, each resolved layout is validated for referential and
 * completeness constraints:
 * <ul>
 *   <li>A declared rule that is absent from the rule registry disables the
 *       layout.</li>
 *   <li>An invocable (non-private) layout without a rule, without a measure, or
 *       with a measure/duration temporal mismatch, is disabled.</li>
 * </ul>
 * Private layouts are consumed as inheritance bases and never enter the
 * invocable registry. Layout defects are non-fatal: defective layouts are
 * omitted and the server continues, since manual sanctions remain available.
 *
 * <p>After boot the registry is immutable and safe for concurrent lookup.
 */
public final class LayoutRegistry implements LifecycleComponent, Reloadable {

    private final KernelLogger log;
    private final RuleRegistry rules;
    private final File dataFolder;
    private final String fileName;
    private final ResourceSaver resourceSaver;
    private final Supplier<List<LayoutDefinition>> stampedSupplier;

    private volatile Map<String, Layout> invocable = Collections.emptyMap();

    /**
     * Provisions the default layout file from the plugin jar when absent.
     */
    @FunctionalInterface
    public interface ResourceSaver {
        void saveIfAbsent(String resourceName);
    }

    private final AnnotationRegistry annotations;

    public LayoutRegistry(final KernelLogger log,
                          final RuleRegistry rules,
                          final AnnotationRegistry annotations,
                          final File dataFolder,
                          final String fileName,
                          final ResourceSaver resourceSaver,
                          final Supplier<List<LayoutDefinition>> stampedSupplier) {
        this.log = log;
        this.rules = rules;
        this.annotations = annotations;
        this.dataFolder = dataFolder;
        this.fileName = fileName;
        this.resourceSaver = resourceSaver;
        this.stampedSupplier = stampedSupplier;
    }

    @Override
    public String tag() {
        return "layouts";
    }

    @Override
    public String reloadTag() {
        return "layouts";
    }

    @Override
    public void reload() {
        // boot() reads files, ingests stamped definitions, and validates against
        // the rule and annotation registries, publishing the invocable map by
        // wholesale assignment; it is idempotent and serves directly as the
        // reload path.
        boot();
    }

    @Override
    public void boot() {
        final File file = new File(dataFolder, fileName);
        if (!file.exists()) {
            log.trace("layouts", "layout file absent; provisioning default '%s'", fileName);
            resourceSaver.saveIfAbsent(fileName);
        }

        log.trace("layouts", "loading layout definitions from '%s'", file.getName());
        final YamlConfiguration yaml = loadExplicit(file);
        final ConfigurationSection root = yaml.getConfigurationSection("layouts");

        final Map<String, LayoutDefinition> definitions =
                root == null ? new HashMap<>() : readDefinitions(root);
        if (root == null) {
            log.trace("layouts", "no 'layouts' section present; relying on stamped definitions");
        }
        ingestStamped(definitions);

        if (definitions.isEmpty()) {
            log.warn("layouts", "no layout definitions present; registry is empty");
            this.invocable = Collections.emptyMap();
            return;
        }
        log.trace("layouts", "definition keys: %s", definitions.keySet());

        final LayoutResolver resolver = new LayoutResolver(log, definitions);
        final Map<String, Layout> resolved = resolver.resolve();

        final Map<String, Layout> accepted = new HashMap<>(resolved.size() * 2);
        int disabled = 0;
        int privateCount = 0;

        for (final Layout layout : resolved.values()) {
            if (layout.rule() != null && !rules.exists(layout.rule())) {
                log.error("layouts", "layout '%s' disabled: references unknown rule '%s'",
                        layout.key(), layout.rule());
                disabled++;
                continue;
            }
            if (layout.isPrivate()) {
                privateCount++;
                continue;
            }
            if (layout.rule() == null) {
                log.error("layouts", "layout '%s' disabled: invocable layout has no rule", layout.key());
                disabled++;
                continue;
            }
            if (layout.measure() == null) {
                log.error("layouts", "layout '%s' disabled: invocable layout has no measure", layout.key());
                disabled++;
                continue;
            }
            final String temporalError = validateTemporal(layout);
            if (temporalError != null) {
                log.error("layouts", "layout '%s' disabled: %s", layout.key(), temporalError);
                disabled++;
                continue;
            }
            accepted.put(layout.key(), layout);
        }

        this.invocable = Collections.unmodifiableMap(accepted);
        log.info("layouts", "registry ready: %d invocable, %d private, %d disabled",
                accepted.size(), privateCount, disabled + resolver.failures());
    }

    /**
     * Merges externally stamped definitions into the file-authored set. A
     * stamped key that collides with a file-authored key is discarded and
     * reported; the file-authored definition prevails.
     *
     * @param definitions the file-authored definition map, mutated in place
     */
    private void ingestStamped(final Map<String, LayoutDefinition> definitions) {
        if (stampedSupplier == null) {
            return;
        }
        final List<LayoutDefinition> stamped = stampedSupplier.get();
        if (stamped == null || stamped.isEmpty()) {
            return;
        }
        int ingested = 0;
        for (final LayoutDefinition def : stamped) {
            if (definitions.putIfAbsent(def.key(), def) != null) {
                log.warn("layouts", "stamped layout '%s' ignored: key already defined in '%s'",
                        def.key(), fileName);
                continue;
            }
            ingested++;
        }
        log.trace("layouts", "ingested %d stamped layout(s)", ingested);
    }

    private Map<String, LayoutDefinition> readDefinitions(final ConfigurationSection root) {
        final Set<String> keys = root.getKeys(false);
        final Map<String, LayoutDefinition> out = new HashMap<>(keys.size() * 2);

        for (final String key : keys) {
            final ConfigurationSection ls = root.getConfigurationSection(key);
            if (ls == null) {
                log.error("layouts", "layout '%s' skipped: not a mapping", key);
                continue;
            }
            try {
                out.put(key, parseDefinition(key, ls));
            } catch (final RuntimeException e) {
                log.error("layouts", "layout '%s' skipped: %s", key, e.getMessage());
            }
        }
        return out;
    }

    private LayoutDefinition parseDefinition(final String key, final ConfigurationSection ls) {
        final boolean isPrivate = key.startsWith("_");
        final String extendsKey = trimOrNull(ls.getString("extends"));
        final String rule = trimOrNull(ls.getString("rule"));
        final String reason = trimOrNull(ls.getString("reason"));

        final DurationSpec duration;
        final Object rawDuration = ls.get("duration");
        duration = rawDuration == null ? null : DurationSpec.parse(String.valueOf(rawDuration).trim());

        final DurationSpec[] escalation;
        final List<?> rawEscalation = ls.getList("escalation");
        if (rawEscalation == null) {
            escalation = null;
        } else {
            escalation = new DurationSpec[rawEscalation.size()];
            for (int i = 0; i < rawEscalation.size(); i++) {
                escalation[i] = DurationSpec.parse(String.valueOf(rawEscalation.get(i)).trim());
            }
        }

        final List<String> rawAnnotations = ls.getStringList("annotations");
        final List<RawAnnotation> parsedAnnotations = new ArrayList<>(rawAnnotations.size());
        for (final String token : rawAnnotations) {
            final RawAnnotation parsed = AnnotationTokenParser.parse(token);
            final String error = annotations.validateForConfig(parsed);
            if (error != null) {
                throw new IllegalArgumentException(error);
            }
            parsedAnnotations.add(parsed);
        }

        final Measure measure;
        final String rawMeasure = trimOrNull(ls.getString("measure"));
        measure = rawMeasure == null ? null : Measure.parse(rawMeasure);

        return new LayoutDefinition(key, isPrivate, extendsKey, rule, reason,
                measure, duration, escalation, parsedAnnotations);
    }

    private static String trimOrNull(final String s) {
        if (s == null) {
            return null;
        }
        final String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private String validateTemporal(final Layout layout) {
        return switch (layout.measure().temporal()) {
            case INSTANTANEOUS -> layout.duration() != null
                    ? "instantaneous measure " + layout.measure() + " must not declare a duration"
                    : null;
            case TEMPORAL -> layout.duration() == null
                    ? "temporal measure " + layout.measure() + " requires a duration"
                    : null;
            case PERMANENT -> null;
        };
    }

    /**
     * Loads the layout file explicitly, surfacing parse failures rather than
     * silently degrading to a partial configuration.
     *
     * @param file the layout file
     * @return the loaded configuration, or an empty configuration on parse error
     */
    private YamlConfiguration loadExplicit(final File file) {
        final YamlConfiguration yaml = new YamlConfiguration();
        if (!file.exists()) {
            return yaml;
        }
        try {
            yaml.load(file);
        } catch (final IOException e) {
            log.error("layouts", e, "failed to read layout file '%s'", file.getName());
        } catch (final InvalidConfigurationException e) {
            log.error("layouts", "layout file '%s' is not valid YAML: %s",
                    file.getName(), e.getMessage());
        }
        return yaml;
    }

    @Override
    public void shutdown() {
        this.invocable = Collections.emptyMap();
    }

    /**
     * Resolves an invocable layout by key.
     *
     * @param key the layout key
     * @return the layout, or null if no invocable layout exists under the key
     */
    public Layout get(final String key) {
        return invocable.get(key);
    }

    /**
     * Reports whether an invocable layout exists under the key.
     *
     * @param key the layout key
     * @return {@code true} if present
     */
    public boolean exists(final String key) {
        return invocable.containsKey(key);
    }

    /**
     * Returns the number of invocable layouts.
     *
     * @return the invocable layout count
     */
    public int size() {
        return invocable.size();
    }

    /**
     * Returns invocable layout keys in ascending lexical order.
     *
     * @return a sorted, unmodifiable view of the invocable keys
     */
    public Set<String> keys() {
        return Collections.unmodifiableSet(new TreeMap<>(invocable).keySet());
    }
}