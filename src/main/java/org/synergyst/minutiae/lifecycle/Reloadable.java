package org.synergyst.minutiae.lifecycle;

/**
 * Contract for a component whose file-backed or configuration-derived state can
 * be rebuilt at runtime without a server restart.
 *
 * <p>A reload rebuilds the component's published state from its sources and
 * swaps it into place atomically, so that collaborators holding a reference to
 * the component observe the new state without re-resolution. Implementations
 * must therefore publish their reloadable state through a single volatile field
 * that is reassigned wholesale, never mutated in place.
 *
 * <p>Reload runs on the server main thread. A failure is reported to the caller;
 * an implementation that fails partway must leave its previously-published state
 * intact rather than a partially-rebuilt one.
 */
public interface Reloadable {

    /**
     * Returns the short, whitespace-free identifier used in reload diagnostics.
     *
     * @return the component tag
     */
    String reloadTag();

    /**
     * Rebuilds and republishes this component's state from its sources.
     *
     * @throws Exception if the rebuild fails; the previously-published state
     *                   must remain in effect
     */
    void reload() throws Exception;
}