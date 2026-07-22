package org.synergyst.minutiae.behaviour;

import org.bukkit.configuration.ConfigurationSection;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable behavioural subsystem configuration.
 *
 * <p>Blocked mute commands are normalised to lowercase and held in a set for
 * constant-time membership tests on the command hot path. The quarantine world
 * is stored by name and resolved at runtime by {@link BehaviourEffects}, since
 * worlds are not guaranteed loaded when configuration is materialised.
 *
 * @param quarantineWorld anchor world name
 * @param quarantineX     anchor X coordinate
 * @param quarantineY     anchor Y coordinate
 * @param quarantineZ     anchor Z coordinate
 * @param quarantineRadius confinement radius in blocks
 * @param blockedCommands lowercased command roots suppressed while muted
 * @param rubberbandLeash maximum permitted per-move displacement in blocks
 */
public record BehaviourConfig(String quarantineWorld,
                              double quarantineX,
                              double quarantineY,
                              double quarantineZ,
                              double quarantineRadius,
                              Set<String> blockedCommands,
                              double rubberbandLeash) {

    /**
     * Materialises configuration from a section, applying defaults for absent
     * values.
     *
     * @param section the {@code behaviour} section, or null for all-defaults
     * @return an immutable configuration snapshot
     */
    public static BehaviourConfig from(final ConfigurationSection section) {
        if (section == null) {
            return new BehaviourConfig("world", 0.5, 100.0, 0.5, 16.0,
                    Set.of(), 3.0);
        }
        final String world = section.getString("quarantine.world", "world");
        final double x = section.getDouble("quarantine.x", 0.5);
        final double y = section.getDouble("quarantine.y", 100.0);
        final double z = section.getDouble("quarantine.z", 0.5);
        final double radius = section.getDouble("quarantine.radius", 16.0);
        final double leash = section.getDouble("rubberband.leash", 3.0);

        final List<String> raw = section.getStringList("mute.blocked-commands");
        final Set<String> blocked = new HashSet<>(raw.size() * 2);
        for (final String cmd : raw) {
            blocked.add(cmd.toLowerCase(Locale.ROOT));
        }

        return new BehaviourConfig(world, x, y, z, radius, Set.copyOf(blocked), leash);
    }
}