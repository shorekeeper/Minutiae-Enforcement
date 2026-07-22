package org.synergyst.minutiae.notify;

import org.bukkit.configuration.ConfigurationSection;
import org.synergyst.minutiae.log.KernelLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable notification configuration: the set of defined channels and the
 * default channel selection.
 *
 * <p>Channels are held in a name-keyed map with lowercased keys for
 * case-insensitive lookup. A channel with an unknown type, a STAFF channel
 * without a permission, or a WEBHOOK channel without a URL is reported and
 * omitted rather than aborting the load.
 *
 * @param channels        defined channels by lowercased name
 * @param defaultChannels channel names used when a caller requests notification
 *                        without naming channels
 */
public record NotifyConfig(Map<String, NotifyChannel> channels, List<String> defaultChannels) {

    /**
     * Materialises configuration from a section, applying empty defaults when
     * the section is absent.
     *
     * @param section the {@code notify} section, or null
     * @param log     diagnostic logger
     * @return an immutable configuration snapshot
     */
    public static NotifyConfig from(final ConfigurationSection section, final KernelLogger log) {
        if (section == null) {
            return new NotifyConfig(Map.of(), List.of());
        }

        final Map<String, NotifyChannel> channels = new HashMap<>(8);
        final ConfigurationSection channelSection = section.getConfigurationSection("channels");
        if (channelSection != null) {
            for (final String name : channelSection.getKeys(false)) {
                final ConfigurationSection cs = channelSection.getConfigurationSection(name);
                if (cs == null) {
                    log.warn("notify", "channel '%s' is not a mapping; skipped", name);
                    continue;
                }
                final ChannelType type = ChannelType.parse(cs.getString("type"));
                if (type == null) {
                    log.warn("notify", "channel '%s' has unknown type '%s'; skipped",
                            name, cs.getString("type"));
                    continue;
                }
                final String permission = cs.getString("permission");
                final String url = cs.getString("url");
                if (type == ChannelType.STAFF && permission == null) {
                    log.warn("notify", "STAFF channel '%s' has no permission; skipped", name);
                    continue;
                }
                if (type == ChannelType.WEBHOOK && url == null) {
                    log.warn("notify", "WEBHOOK channel '%s' has no url; skipped", name);
                    continue;
                }
                final String key = name.toLowerCase(Locale.ROOT);
                channels.put(key, new NotifyChannel(name, type, permission, url));
            }
        }

        final List<String> defaults = new ArrayList<>();
        for (final String name : section.getStringList("default-channels")) {
            defaults.add(name.toLowerCase(Locale.ROOT));
        }

        return new NotifyConfig(Map.copyOf(channels), List.copyOf(defaults));
    }
}