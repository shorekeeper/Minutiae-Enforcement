package org.synergyst.minutiae.engine;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Synthetic authority under which the automaton engine issues sanctions.
 *
 * <p>The actor carries a display name used for staff attribution and a permission
 * set that bounds what automation may do. Automatic issuance is gated by the
 * same resolver that gates a manual command; the resolver consults this actor's
 * predicate rather than a live sender, so automation can never exceed its
 * configured grant. When {@code allowAll} is set the actor holds every
 * permission, a configuration reserved for trusted deployments.
 *
 * @param name        staff-attribution name
 * @param permissions explicitly granted permission nodes
 * @param allowAll    whether every permission is held
 */
public record SystemActor(String name, Set<String> permissions, boolean allowAll) {

    /**
     * Tests whether the actor holds a permission node.
     *
     * @param node the node
     * @return {@code true} when granted
     */
    public boolean has(final String node) {
        return allowAll || permissions.contains(node);
    }

    /**
     * Returns the actor's permission predicate.
     *
     * @return a predicate testing permission membership
     */
    public Predicate<String> predicate() {
        return this::has;
    }
}