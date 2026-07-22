package org.synergyst.minutiae.engine;

import org.bukkit.configuration.ConfigurationSection;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable safety configuration of the automatic-enforcement engine.
 *
 * <p>The configuration governs the fail-safe posture of automatic issuance. An
 * automaton not present in the armed set, unless global arming is enabled, is
 * evaluated in forced dry-run: its plan resolves and reports but applies no
 * effect. The throttle bounds the number of firings an automaton may perform
 * per minute; exceeding it self-mutes the automaton. The system actor bounds
 * what automation may do at all.
 *
 * @param armedGlobally  whether every automaton is armed
 * @param armedAutomata  individually armed automaton names
 * @param throttlePerMin firings per minute permitted per automaton
 * @param systemActor    the synthetic issuing authority
 */
public record SafetyConfig(boolean armedGlobally,
                           Set<String> armedAutomata,
                           int throttlePerMin,
                           SystemActor systemActor) {

    /**
     * Materialises configuration from a section, applying fail-safe defaults.
     *
     * @param section the {@code alam} section, or null
     * @return an immutable configuration snapshot
     */
    public static SafetyConfig from(final ConfigurationSection section) {
        if (section == null) {
            return new SafetyConfig(false, Set.of(), 30,
                    new SystemActor("SYSTEM", Set.of(), false));
        }
        final boolean armedGlobally = section.getBoolean("armed", false);
        final Set<String> armed = new HashSet<>(section.getStringList("armed-automata"));
        final int throttle = Math.max(1, section.getInt("throttle.per-minute", 30));

        final ConfigurationSection sys = section.getConfigurationSection("system");
        final String name = sys == null ? "SYSTEM" : sys.getString("name", "SYSTEM");
        final boolean allowAll = sys != null && sys.getBoolean("allow-all", false);
        final List<String> perms = sys == null ? List.of() : sys.getStringList("permissions");
        final SystemActor actor = new SystemActor(name, new HashSet<>(perms), allowAll);

        return new SafetyConfig(armedGlobally, armed, throttle, actor);
    }

    /**
     * Reports whether an automaton is armed for live effect.
     *
     * @param automaton the automaton name
     * @return {@code true} when armed
     */
    public boolean isArmed(final String automaton) {
        return armedGlobally || armedAutomata.contains(automaton);
    }
}