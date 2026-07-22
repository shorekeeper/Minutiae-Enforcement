package org.synergyst.minutiae.message;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;

/**
 * A single locale's message templates.
 *
 * <p>Templates for the closed key set are held in an array indexed by
 * {@link MessageKey#ordinal()} for constant-time retrieval without hashing. A
 * template absent from the backing configuration is stored as null and
 * resolved through the fallback chain by the message service. A multi-valued
 * configuration entry (a YAML list) is joined with newlines into a single
 * multi-line template during loading.
 *
 * <p>Alongside the keyed array, the bundle retains a flat map of every string
 * leaf in the configuration, keyed by its dotted path. This dynamic layer
 * serves namespaces whose entries are operator-defined and therefore cannot be
 * enumerated in {@link MessageKey}, such as rank display names under
 * {@code rank.<id>}. List leaves are joined with newlines identically to keyed
 * templates. The map costs a few kilobytes per bundle and is built once at
 * load.
 *
 * <p>A bundle is immutable after construction and safe for concurrent reads.
 */
public final class MessageBundle {

    private final String tag;
    private final String[] templates;
    private final Map<String, String> dynamic;

    private MessageBundle(final String tag, final String[] templates,
                          final Map<String, String> dynamic) {
        this.tag = tag;
        this.templates = templates;
        this.dynamic = dynamic;
    }

    /**
     * Loads a bundle from a configuration.
     *
     * @param tag configuration-derived locale tag (the filename without
     *            extension, lowercased)
     * @param cfg the loaded bundle configuration
     * @return the bundle
     */
    public static MessageBundle load(final String tag, final FileConfiguration cfg) {
        final MessageKey[] keys = MessageKey.values();
        final String[] templates = new String[keys.length];
        for (final MessageKey key : keys) {
            final String path = key.path();
            if (cfg.isList(path)) {
                templates[key.ordinal()] = String.join("\n", cfg.getStringList(path));
            } else if (cfg.isString(path)) {
                templates[key.ordinal()] = cfg.getString(path);
            }
        }
        final Map<String, String> dynamic = new HashMap<>(64);
        for (final String path : cfg.getKeys(true)) {
            if (cfg.isString(path)) {
                dynamic.put(path, cfg.getString(path));
            } else if (cfg.isList(path)) {
                dynamic.put(path, String.join("\n", cfg.getStringList(path)));
            }
        }
        return new MessageBundle(tag, templates, Map.copyOf(dynamic));
    }

    /** Returns the bundle's locale tag. */
    public String tag() {
        return tag;
    }

    /**
     * Returns the template for a key, or null when this bundle does not define
     * it.
     *
     * @param key the message key
     * @return the template, or null
     */
    public String template(final MessageKey key) {
        return templates[key.ordinal()];
    }

    /**
     * Returns the template at a dotted path, or null when this bundle does not
     * define it. Serves operator-defined namespaces outside the closed key set.
     *
     * @param path the dotted configuration path
     * @return the template, or null
     */
    public String dynamic(final String path) {
        return dynamic.get(path);
    }
}