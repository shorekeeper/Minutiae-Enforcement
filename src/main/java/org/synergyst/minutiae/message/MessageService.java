package org.synergyst.minutiae.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.synergyst.minutiae.lifecycle.LifecycleComponent;
import org.synergyst.minutiae.lifecycle.Reloadable;
import org.synergyst.minutiae.log.KernelLogger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Renders and dispatches user-facing messages from locale bundles authored in
 * the legacy ampersand format.
 *
 * <p>Templates use the {@code &} colour code convention extended with hex codes
 * of the form {@code &#rrggbb}, covering the full colour range introduced in
 * Minecraft 1.16. Placeholders are written as {@code {name}}. Scalar
 * placeholders are substituted into the template string before deserialisation;
 * their values are sanitised of colour codes so that user-supplied content
 * cannot inject formatting. Rich placeholders are rendered to components and
 * spliced into the result, which is how interactive elements such as the reason
 * hover card are produced without any markup in the template.
 *
 * <p>On boot the service provisions the default English bundle when the language
 * directory is empty, then loads every {@code lang/<tag>.yml} into a bundle keyed
 * by its lowercased tag. Bundle selection for a recipient follows a per-key
 * fallback chain: the recipient's exact locale tag, then its language portion,
 * then the configured default locale, then the jar-embedded bundle of the
 * default locale, then the jar-embedded English bundle.
 *
 * <p>The embedded fallback layer exists because on-disk bundles are provisioned
 * once and never overwritten: a plugin update that introduces new message keys
 * cannot amend an operator's existing files. Without the embedded layer, every
 * such key would render as a visible placeholder until the operator merged the
 * new entries by hand. With it, an on-disk bundle overrides key by key while
 * the shipped bundle fills every gap; a bundle whose key count lags the code is
 * reported once at load so the operator knows a merge is available.
 *
 * <p>The rendered prefix is injected into every template through the
 * {@code {prefix}} token, drawn unsanitised from the {@code prefix} entry so
 * that its own colour codes apply. All published state is either immutable or
 * held in concurrent structures; the service is safe for use from any thread,
 * while recipient delivery observes the platform's threading rules as ensured by
 * callers.
 */
public final class MessageService implements LifecycleComponent, Reloadable {

    private static final String LANG_DIR = "lang";
    private static final String DEFAULT_RESOURCE = "lang/en.yml";
    private static final String ENGLISH_TAG = "en";
    private static final char SECTION_SIGN = '\u00A7';
    private static final String ELLIPSIS = "\u2026";
    private final Map<String, String[]> unitCache = new ConcurrentHashMap<>(8);

    private final KernelLogger log;
    private final File dataFolder;
    private final ResourceSaver resourceSaver;
    private final ResourceReader resourceReader;
    private final String defaultLocale;
    private final boolean perPlayerLocale;
    private final int reasonAbbreviate;

    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();
    private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();

    private volatile Map<String, MessageBundle> bundles = new HashMap<>(8);
    private volatile MessageBundle embeddedDefault;
    private volatile MessageBundle embeddedEnglish;
    private final Map<String, String> prefixCache = new ConcurrentHashMap<>(8);
    private final java.util.Set<MessageKey> warnedMissing = new CopyOnWriteArraySet<>();

    /**
     * Provisions a bundled language resource to the data folder when absent.
     */
    @FunctionalInterface
    public interface ResourceSaver {
        void saveIfAbsent(String resourceName);
    }

    /**
     * Opens a bundled resource from the plugin jar.
     */
    @FunctionalInterface
    public interface ResourceReader {

        /**
         * Opens a jar-relative resource for reading.
         *
         * @param resourceName the jar-relative resource name
         * @return the stream, or null when no such resource is bundled
         */
        InputStream open(String resourceName);
    }

    public MessageService(final KernelLogger log,
                          final File dataFolder,
                          final ResourceSaver resourceSaver,
                          final ResourceReader resourceReader,
                          final String defaultLocale,
                          final boolean perPlayerLocale,
                          final int reasonAbbreviate) {
        this.log = log;
        this.dataFolder = dataFolder;
        this.resourceSaver = resourceSaver;
        this.resourceReader = resourceReader;
        this.defaultLocale = defaultLocale.toLowerCase(Locale.ROOT);
        this.perPlayerLocale = perPlayerLocale;
        this.reasonAbbreviate = Math.max(1, reasonAbbreviate);
    }

    @Override
    public String tag() {
        return "messages";
    }

    @Override
    public void boot() {
        loadBundles();
    }

    @Override
    public String reloadTag() {
        return "messages";
    }

    @Override
    public void reload() {
        loadBundles();
        prefixCache.clear();
        unitCache.clear();
        warnedMissing.clear();
    }

    private void loadBundles() {
        final File langDir = new File(dataFolder, LANG_DIR);

        // Always provision the default English bundle and the bundle for the
        // configured default locale, each only when absent. This guarantees the
        // fallback chain terminates in a present bundle even when the default
        // locale is not English.
        provisionIfMissing(langDir, DEFAULT_RESOURCE);
        provisionIfMissing(langDir, LANG_DIR + "/" + defaultLocale + ".yml");

        // The embedded bundles are the terminal fallback layer: on-disk files
        // are provisioned once and never overwritten, so keys introduced after
        // provisioning exist only here until the operator merges them.
        this.embeddedEnglish = loadEmbedded(ENGLISH_TAG);
        this.embeddedDefault = defaultLocale.equals(ENGLISH_TAG)
                ? embeddedEnglish : loadEmbedded(defaultLocale);

        final File[] files = langDir.listFiles((dir, name) -> name.endsWith(".yml"));
        final Map<String, MessageBundle> loaded = new HashMap<>(8);
        if (files == null || files.length == 0) {
            log.warn("messages", "no language bundles present; embedded defaults only");
            this.bundles = loaded;
            return;
        }
        for (final File file : files) {
            final String bundleTag = stripExtension(file.getName()).toLowerCase(Locale.ROOT);
            final YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            final MessageBundle bundle = MessageBundle.load(bundleTag, cfg);
            loaded.put(bundleTag, bundle);
            reportStaleness(bundleTag, bundle);
        }
        if (!loaded.containsKey(defaultLocale)) {
            log.warn("messages", "default locale '%s' has no on-disk bundle; embedded"
                    + " defaults apply", defaultLocale);
        }
        this.bundles = loaded;
        log.info("messages", "loaded %d bundle(s), default=%s, per-player=%b, format=legacy(&)+hex",
                loaded.size(), defaultLocale, perPlayerLocale);
    }

    /**
     * Reports the number of keys an on-disk bundle does not define. Such keys
     * are served from the embedded fallback; the report tells the operator a
     * merge with the shipped bundle is available.
     *
     * @param tag    the bundle tag
     * @param bundle the loaded on-disk bundle
     */
    private void reportStaleness(final String tag, final MessageBundle bundle) {
        int missing = 0;
        for (final MessageKey key : MessageKey.values()) {
            if (bundle.template(key) == null) {
                missing++;
            }
        }
        if (missing > 0) {
            log.warn("messages", "bundle '%s' lacks %d key(s); embedded defaults apply."
                            + " Merge lang/%s.yml with the shipped bundle to localise them.",
                    tag, missing, tag);
        }
    }

    /**
     * Loads a bundle directly from the plugin jar.
     *
     * @param tag the locale tag
     * @return the embedded bundle, or null when the jar carries none
     */
    private MessageBundle loadEmbedded(final String tag) {
        final String resource = LANG_DIR + "/" + tag + ".yml";
        try (final InputStream in = resourceReader.open(resource)) {
            if (in == null) {
                return null;
            }
            final YamlConfiguration cfg = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            return MessageBundle.load(tag, cfg);
        } catch (final IOException e) {
            log.warn("messages", "embedded bundle '%s' unreadable: %s", resource, e.getMessage());
            return null;
        }
    }

    /**
     * Provisions a bundled language resource when the target file is absent.
     * A resource that does not exist inside the jar is skipped silently, so a
     * default locale without a shipped bundle does not raise an error.
     *
     * @param langDir      the language directory
     * @param resourceName the jar-relative resource name
     */
    private void provisionIfMissing(final File langDir, final String resourceName) {
        final String leaf = resourceName.substring(resourceName.lastIndexOf('/') + 1);
        if (new File(langDir, leaf).exists()) {
            return;
        }
        try {
            resourceSaver.saveIfAbsent(resourceName);
            log.trace("messages", "provisioned bundle '%s'", resourceName);
        } catch (final IllegalArgumentException notInJar) {
            // No shipped bundle for this locale; operators may add one manually.
            log.trace("messages", "no bundled resource '%s' to provision", resourceName);
        }
    }

    @Override
    public void shutdown() {
        bundles.clear();
        embeddedDefault = null;
        embeddedEnglish = null;
        prefixCache.clear();
        unitCache.clear();
        warnedMissing.clear();
    }

    /**
     * Renders a message for a specific locale.
     *
     * @param localeTag recipient locale tag, or null for the default locale
     * @param key       the message key
     * @param args      scalar and rich arguments
     * @return the rendered component
     */
    public Component render(final String localeTag, final MessageKey key, final Arg... args) {
        String template = resolveTemplate(localeTag, key);
        if (template == null) {
            if (warnedMissing.add(key)) {
                log.warn("messages", "no template for '%s' in any bundle, embedded included",
                        key.path());
            }
            return Component.text("{" + key.path() + "}");
        }

        Map<String, RichValue> rich = null;
        for (final Arg arg : args) {
            if (arg.isRich()) {
                if (rich == null) {
                    rich = new HashMap<>(4);
                }
                rich.put(arg.name(), arg.rich());
            } else {
                template = replace(template, arg.name(), sanitise(arg.scalar()));
            }
        }
        template = replace(template, "prefix", rawPrefix(localeTag));

        if (rich == null || rich.isEmpty()) {
            return legacy.deserialize(template);
        }
        return splice(template, rich, localeTag);
    }

    /**
     * Renders a message for a recipient and delivers it.
     *
     * @param to   the recipient
     * @param key  the message key
     * @param args scalar and rich arguments
     */
    public void send(final CommandSender to, final MessageKey key, final Arg... args) {
        to.sendMessage(render(localeTagFor(to), key, args));
    }

    /**
     * Renders a message for a recipient and serialises it to plain text.
     *
     * <p>Intended for localisable fragments substituted as scalar arguments
     * into another template. Any colour codes carried by the fragment template
     * are discarded by serialisation, which matches the sanitisation applied
     * to every scalar argument on the send path.
     *
     * @param to   the recipient whose locale selects the bundle
     * @param key  the message key
     * @param args scalar and rich arguments
     * @return the plain-text rendering
     */
    public String plain(final CommandSender to, final MessageKey key, final Arg... args) {
        return plainText.serialize(render(localeTagFor(to), key, args));
    }

    /**
     * Renders a message for a locale tag and serialises it to plain text.
     *
     * <p>Serves contexts where no recipient sender exists yet, such as the
     * pre-login refusal screen; a null tag selects the default locale.
     *
     * @param localeTag the locale tag, or null for the default locale
     * @param key       the message key
     * @param args      scalar and rich arguments
     * @return the plain-text rendering
     */
    public String plain(final String localeTag, final MessageKey key, final Arg... args) {
        return plainText.serialize(render(localeTag, key, args));
    }

    /**
     * Resolves the locale tag applicable to a recipient.
     *
     * @param to the recipient
     * @return the recipient's locale tag when per-player locales are enabled and
     *         the recipient is a player, otherwise the default locale
     */
    public String localeTagFor(final CommandSender to) {
        if (perPlayerLocale && to instanceof Player player) {
            return player.locale().toString().toLowerCase(Locale.ROOT);
        }
        return defaultLocale;
    }

    /**
     * Abbreviates a reason to the configured length, appending an ellipsis when
     * truncated.
     *
     * @param reason the full reason, or null
     * @return the abbreviated reason, or a dash when the reason is null or blank
     */
    public String abbreviate(final String reason) {
        if (reason == null || reason.isBlank()) {
            return "-";
        }
        if (reason.length() <= reasonAbbreviate) {
            return reason;
        }
        return reason.substring(0, reasonAbbreviate - 1) + ELLIPSIS;
    }

    // ----------------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------------

    private Component splice(final String template, final Map<String, RichValue> rich,
                             final String localeTag) {
        Component out = Component.empty();
        int i = 0;
        final int n = template.length();
        while (i < n) {
            final int lb = template.indexOf('{', i);
            if (lb < 0) {
                out = out.append(legacy.deserialize(template.substring(i)));
                break;
            }
            final int rb = template.indexOf('}', lb);
            if (rb < 0) {
                out = out.append(legacy.deserialize(template.substring(i)));
                break;
            }
            final String name = template.substring(lb + 1, rb);
            final RichValue value = rich.get(name);
            if (value == null) {
                // Not a rich token: emit the text up to and including the brace
                // as a literal and continue scanning.
                out = out.append(legacy.deserialize(template.substring(i, rb + 1)));
                i = rb + 1;
            } else {
                if (lb > i) {
                    out = out.append(legacy.deserialize(template.substring(i, lb)));
                }
                out = out.append(value.render(this, localeTag));
                i = rb + 1;
            }
        }
        return out;
    }

    private String rawPrefix(final String localeTag) {
        final String tag = localeTag == null ? defaultLocale : localeTag.toLowerCase(Locale.ROOT);
        return prefixCache.computeIfAbsent(tag, ignored -> {
            final String template = resolveTemplate(tag, MessageKey.PREFIX);
            return template == null ? "" : template;
        });
    }

    /**
     * Resolves a template through the fallback chain: the exact on-disk tag,
     * the on-disk language portion, the on-disk default locale, the embedded
     * default-locale bundle, and the embedded English bundle.
     *
     * @param localeTag the recipient locale tag, or null
     * @param key       the message key
     * @return the template, or null when no layer defines it
     */
    private String resolveTemplate(final String localeTag, final MessageKey key) {
        final String tag = localeTag == null ? defaultLocale : localeTag.toLowerCase(Locale.ROOT);

        MessageBundle bundle = bundles.get(tag);
        if (bundle != null && bundle.template(key) != null) {
            return bundle.template(key);
        }
        final int underscore = tag.indexOf('_');
        if (underscore > 0) {
            bundle = bundles.get(tag.substring(0, underscore));
            if (bundle != null && bundle.template(key) != null) {
                return bundle.template(key);
            }
        }
        bundle = bundles.get(defaultLocale);
        if (bundle != null && bundle.template(key) != null) {
            return bundle.template(key);
        }
        final MessageBundle shippedDefault = embeddedDefault;
        if (shippedDefault != null && shippedDefault.template(key) != null) {
            return shippedDefault.template(key);
        }
        final MessageBundle shippedEnglish = embeddedEnglish;
        return shippedEnglish == null ? null : shippedEnglish.template(key);
    }

    /**
     * Renders a duration in localised compact form.
     *
     * <p>The decomposition mirrors {@link DurationSpec#format()}: largest
     * non-zero units first, week through second, with unit suffixes drawn from
     * the recipient locale's bundle. The permanent duration renders through
     * {@link MessageKey#DURATION_PERMANENT}; the zero span renders as zero
     * seconds. The five unit suffixes are resolved once per locale and cached,
     * so a broadcast loop performs plain string assembly per recipient.
     *
     * @param localeTag the recipient locale tag, or null for the default
     * @param d         the duration, or null
     * @return the localised rendering; a dash for a null duration
     */
    public String durationText(final String localeTag, final org.synergyst.minutiae.time.DurationSpec d) {
        if (d == null) {
            return plain(localeTag, MessageKey.DURATION_NONE);
        }
        if (d.permanent()) {
            return plain(localeTag, MessageKey.DURATION_PERMANENT);
        }
        final String tag = localeTag == null ? defaultLocale : localeTag.toLowerCase(Locale.ROOT);
        final String[] units = unitCache.computeIfAbsent(tag, t -> new String[]{
                plain(t, MessageKey.DURATION_UNIT_WEEKS),
                plain(t, MessageKey.DURATION_UNIT_DAYS),
                plain(t, MessageKey.DURATION_UNIT_HOURS),
                plain(t, MessageKey.DURATION_UNIT_MINUTES),
                plain(t, MessageKey.DURATION_UNIT_SECONDS)});
        long ms = d.millis();
        if (ms == 0L) {
            return "0" + units[4];
        }
        final long[] spans = {604_800_000L, 86_400_000L, 3_600_000L, 60_000L, 1_000L};
        final StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < spans.length; i++) {
            final long q = ms / spans[i];
            if (q > 0L) {
                sb.append(q).append(units[i]);
            }
            ms %= spans[i];
        }
        return sb.toString();
    }

    /**
     * Renders a dynamic-namespace template to a component.
     *
     * <p>Resolution follows the same fallback chain as keyed templates: the
     * exact on-disk tag, the language portion, the default locale, then the
     * embedded default and English bundles. The template is deserialised
     * through the legacy format, so operator-authored entries (rank display
     * names) may carry colour codes. An undefined path renders the fallback
     * verbatim.
     *
     * @param localeTag the recipient locale tag, or null for the default
     * @param path      the dotted bundle path
     * @param fallback  text rendered when no bundle defines the path
     * @return the rendered component
     */
    public Component dynamic(final String localeTag, final String path, final String fallback) {
        final String template = resolveDynamic(localeTag, path);
        return legacy.deserialize(template != null ? template : fallback);
    }

    /**
     * Resolves a dynamic template through the bundle fallback chain.
     *
     * @param localeTag the recipient locale tag, or null
     * @param path      the dotted bundle path
     * @return the template, or null when no layer defines it
     */
    private String resolveDynamic(final String localeTag, final String path) {
        final String tag = localeTag == null ? defaultLocale : localeTag.toLowerCase(Locale.ROOT);
        MessageBundle bundle = bundles.get(tag);
        if (bundle != null && bundle.dynamic(path) != null) {
            return bundle.dynamic(path);
        }
        final int underscore = tag.indexOf('_');
        if (underscore > 0) {
            bundle = bundles.get(tag.substring(0, underscore));
            if (bundle != null && bundle.dynamic(path) != null) {
                return bundle.dynamic(path);
            }
        }
        bundle = bundles.get(defaultLocale);
        if (bundle != null && bundle.dynamic(path) != null) {
            return bundle.dynamic(path);
        }
        final MessageBundle shippedDefault = embeddedDefault;
        if (shippedDefault != null && shippedDefault.dynamic(path) != null) {
            return shippedDefault.dynamic(path);
        }
        final MessageBundle shippedEnglish = embeddedEnglish;
        return shippedEnglish == null ? null : shippedEnglish.dynamic(path);
    }

    /**
     * Renders the first defined template among a chain of dynamic paths.
     *
     * <p>Each path is resolved through the full bundle fallback chain before
     * the next is tried, so a specific form defined only in the embedded
     * bundle still wins over a base entry defined on disk being consulted for
     * the wrong path. When no path resolves anywhere, the fallback text is
     * rendered verbatim.
     *
     * @param localeTag the recipient locale tag, or null for the default
     * @param paths     the dotted bundle paths, most specific first
     * @param fallback  text rendered when no path is defined
     * @return the rendered component
     */
    public Component dynamicChain(final String localeTag, final String[] paths,
                                  final String fallback) {
        for (final String path : paths) {
            final String template = resolveDynamic(localeTag, path);
            if (template != null) {
                return legacy.deserialize(template);
            }
        }
        return legacy.deserialize(fallback);
    }

    private static String replace(final String template, final String name, final String value) {
        return template.replace("{" + name + "}", value);
    }

    private static String sanitise(final String value) {
        if (value.indexOf('&') < 0 && value.indexOf(SECTION_SIGN) < 0) {
            return value;
        }
        final StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0, n = value.length(); i < n; i++) {
            final char c = value.charAt(i);
            if (c != '&' && c != SECTION_SIGN) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isEmpty(final File dir) {
        final String[] entries = dir.list();
        return entries == null || entries.length == 0;
    }

    private static String stripExtension(final String name) {
        final int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}