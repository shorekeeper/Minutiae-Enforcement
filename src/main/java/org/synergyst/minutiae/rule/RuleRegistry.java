package org.synergyst.minutiae.rule;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.synergyst.minutiae.lifecycle.LifecycleComponent;
import org.synergyst.minutiae.lifecycle.Reloadable;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.storage.RuleSyncResult;
import org.synergyst.minutiae.storage.Storage;
import org.synergyst.minutiae.storage.StorageException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.io.IOException;
import org.bukkit.configuration.InvalidConfigurationException;

/**
 * Authoritative in-memory rule registry.
 *
 * <p>On boot the registry loads rule definitions from the configured file,
 * validates each identifier against the canonical grammar, rejects malformed or
 * empty entries, and materialises an immutable {@link RuleTable}. A malformed
 * entry is fatal: the boot sequence aborts rather than proceeding with a partial
 * registry, since layout referential integrity depends on completeness.
 *
 * <p>Following construction the registry reconciles the persisted rule cache
 * with the loaded set. Reconciliation is dispatched to the asynchronous storage
 * scheduler; the boot thread joins on its completion so that the resulting
 * counts appear inline in the boot diagnostics. The join occurs during server
 * enable, before tick processing begins, and does not affect steady-state
 * performance.
 *
 * <p>After boot the registry is read-only and safe for concurrent access.
 */
public final class RuleRegistry implements LifecycleComponent, Reloadable {

    private final KernelLogger log;
    private final Storage storage;
    private final File dataFolder;
    private final String rulesFileName;
    private final ResourceSaver resourceSaver;

    private volatile RuleTable table;

    /**
     * Callback that provisions the default rules file from the plugin jar when
     * absent. Abstracted to keep the registry independent of the plugin type.
     */
    @FunctionalInterface
    public interface ResourceSaver {
        void saveIfAbsent(String resourceName);
    }

    public RuleRegistry(final KernelLogger log,
                        final Storage storage,
                        final File dataFolder,
                        final String rulesFileName,
                        final ResourceSaver resourceSaver) {
        this.log = log;
        this.storage = storage;
        this.dataFolder = dataFolder;
        this.rulesFileName = rulesFileName;
        this.resourceSaver = resourceSaver;
    }

    @Override
    public String tag() {
        return "rules";
    }

    @Override
    public void boot() {
        loadAndPublish();
        synchroniseCache();
    }

    @Override
    public String reloadTag() {
        return "rules";
    }

    @Override
    public void reload() {
        loadAndPublish();
        synchroniseCache();
    }

    private void loadAndPublish() {
        final File file = new File(dataFolder, rulesFileName);
        if (!file.exists()) {
            log.trace("rules", "rules file absent; provisioning default '%s'", rulesFileName);
            resourceSaver.saveIfAbsent(rulesFileName);
        }

        log.trace("rules", "loading rule definitions from '%s'", file.getName());
        final YamlConfiguration yaml = loadWithSafeSeparator(file);
        final ConfigurationSection section = yaml.getConfigurationSection("rules");
        if (section == null) {
            throw new IllegalStateException("rules file has no 'rules' section: " + file);
        }

        final Set<String> keys = section.getKeys(false);
        final List<Rule> parsed = new ArrayList<>(keys.size());
        int rejected = 0;
        for (final String id : keys) {
            final String violation = RuleId.validate(id);
            if (violation != null) {
                log.error("rules", "rejecting malformed identifier '%s': %s", id, violation);
                rejected++;
                continue;
            }
            final String description = section.getString(id, "").trim();
            if (description.isEmpty()) {
                log.error("rules", "rejecting rule '%s': empty description", id);
                rejected++;
                continue;
            }
            parsed.add(Rule.of(id, description));
        }
        if (rejected > 0) {
            throw new IllegalStateException(rejected + " malformed rule(s) present; refusing to load");
        }
        if (parsed.isEmpty()) {
            throw new IllegalStateException("rule registry is empty");
        }

        // Atomic publication: build the new table, then swap the volatile field.
        this.table = RuleTable.build(parsed);
        log.info("rules", "loaded %d rule(s)", table.size());
    }

    private void synchroniseCache() {
        log.trace("rules", "reconciling persisted rule cache");
        final RuleSyncResult result;
        try {
            result = storage.syncRuleCache(table.ids(), table.descriptions(), table.hashes()).join();
        } catch (final CompletionException e) {
            final Throwable cause = e.getCause();
            throw new StorageException("rule cache synchronisation failed",
                    cause != null ? cause : e);
        }
        if (result.mutated()) {
            log.info("rules", "cache reconciled: +%d added, ~%d changed, -%d removed (%d unchanged)",
                    result.added(), result.changed(), result.removed(), result.unchanged());
        } else {
            log.info("rules", "cache up to date (%d unchanged)", result.unchanged());
        }
    }

    /**
     * Loads a YAML file with a path separator that cannot occur in rule
     * identifiers.
     *
     * <p>The default configuration path separator is {@code '.'}, which Bukkit
     * uses to split nested keys. Rule identifiers contain dots by design
     * (e.g. {@code P.3.2}); under the default separator such a key would be
     * decomposed into a nested section rather than retained as a literal key,
     * collapsing the entire registry under its first segment. Setting the
     * separator to NUL, a character excluded from the identifier grammar,
     * guarantees that dotted keys survive both loading and subsequent lookup
     * intact.
     *
     * @param file the file to load
     * @return the loaded configuration
     */
    private static YamlConfiguration loadWithSafeSeparator(final File file) {
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().pathSeparator('\u0000');
        try {
            yaml.load(file);
        } catch (final IOException | InvalidConfigurationException e) {
            throw new IllegalStateException("failed to load rule file: " + file.getName(), e);
        }
        return yaml;
    }

    @Override
    public void shutdown() {
        this.table = null;
    }

    /**
     * Reports whether a rule identifier is defined.
     *
     * @param id the identifier
     * @return {@code true} if defined
     */
    public boolean exists(final String id) {
        return table != null && table.contains(id);
    }

    /**
     * Returns the description for a rule identifier.
     *
     * @param id the identifier
     * @return the description, or {@code null} if undefined
     */
    public String describe(final String id) {
        return table == null ? null : table.description(id);
    }

    /**
     * Returns the number of loaded rules.
     *
     * @return the rule count
     */
    public int size() {
        return table == null ? 0 : table.size();
    }

    /**
     * Returns a read-only view of the loaded rule identifiers. The returned
     * reference is the live backing array and must not be mutated.
     *
     * @return the identifier array
     */
    public String[] identifiers() {
        return table == null ? new String[0] : table.ids();
    }
}