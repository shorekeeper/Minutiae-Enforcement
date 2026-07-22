package org.synergyst.minutiae.rank;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Immutable operator-defined rank catalogue: an ordered mapping from
 * permission nodes to rank identifiers, used to attribute sanctions to the
 * issuer's role rather than assuming a uniform moderator.
 *
 * <p>Entries are declared in configuration in priority order; resolution scans
 * them in that order and returns the first entry whose permission the sender
 * holds. Operators therefore list the most privileged rank first, since a
 * senior staff member typically also holds junior nodes. Display names live in
 * the language bundles under the dynamic {@code rank.<id>} namespace and are
 * rendered per recipient locale; this class carries identifiers and permission
 * nodes only.
 *
 * <p>Two identifiers are special-cased. The console identifier is returned for
 * the console sender without any permission scan, because the console holds
 * every node and would otherwise always match the first entry. The default
 * identifier is returned when no entry matches, which also covers synthetic
 * senders (the web actor, the system actor) whose scoped permission sets carry
 * no rank nodes.
 *
 * <p>The two parallel arrays are ordinal-aligned; the catalogue is built once
 * from configuration and never mutated, so resolution is a lock-free linear
 * scan over a handful of entries. Permission tests against a {@link Player}
 * touch live Bukkit state and are safe only where the caller already runs on
 * the main thread; non-player senders resolve without any Bukkit call.
 *
 * @param ids         rank identifiers, priority order
 * @param permissions permission nodes, parallel to {@code ids}
 * @param defaultId   identifier returned when no entry matches
 * @param consoleId   identifier returned for the console sender
 */
public record RankConfig(String[] ids, String[] permissions, String defaultId, String consoleId) {

    /**
     * Materialises the catalogue from a section, applying fail-safe defaults.
     *
     * <p>A malformed entry (not a mapping, or missing {@code id} or
     * {@code permission}) is skipped; a defect in one entry does not abort the
     * load. An absent section yields an empty catalogue in which every sender
     * resolves to the default identifier.
     *
     * @param section the {@code ranks} section, or null
     * @return an immutable catalogue
     */
    public static RankConfig from(final ConfigurationSection section) {
        if (section == null) {
            return new RankConfig(new String[0], new String[0], "staff", "console");
        }
        final String defaultId = section.getString("default", "staff");
        final String consoleId = section.getString("console", "console");
        final List<String> ids = new ArrayList<>(8);
        final List<String> perms = new ArrayList<>(8);
        for (final Object raw : section.getList("entries", List.of())) {
            if (!(raw instanceof Map<?, ?> map)) {
                continue;
            }
            final Object id = map.get("id");
            final Object permission = map.get("permission");
            if (id == null || permission == null) {
                continue;
            }
            ids.add(String.valueOf(id));
            perms.add(String.valueOf(permission));
        }
        return new RankConfig(ids.toArray(new String[0]), perms.toArray(new String[0]),
                defaultId, consoleId);
    }

    /**
     * Resolves the rank identifier of a sender.
     *
     * @param sender the issuing sender
     * @return the console identifier for the console, the first matching
     *         entry's identifier for any other sender, or the default
     *         identifier when nothing matches
     */
    public String resolve(final CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) {
            return consoleId;
        }
        for (int i = 0; i < ids.length; i++) {
            if (sender.hasPermission(permissions[i])) {
                return ids[i];
            }
        }
        return defaultId;
    }
}