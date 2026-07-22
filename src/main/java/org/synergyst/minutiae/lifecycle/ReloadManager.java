package org.synergyst.minutiae.lifecycle;

import org.synergyst.minutiae.log.KernelLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered registry of reloadable components.
 *
 * <p>Components are reloaded in registration order, which callers arrange to
 * respect inter-component dependencies: sources that others validate against are
 * reloaded first. Each component's failure is caught, logged, and counted; a
 * failing component does not halt the reload of the remainder, since its own
 * contract guarantees its prior state survives a failed rebuild.
 *
 * <p>The manager is main-thread only, consistent with the reload contract.
 */
public final class ReloadManager {

    /**
     * Aggregate result of a reload pass.
     *
     * @param succeeded number of components reloaded successfully
     * @param failed    number of components whose reload threw
     */
    public record Result(int succeeded, int failed) {
    }

    private final KernelLogger log;
    private final List<Reloadable> components = new ArrayList<>(8);

    public ReloadManager(final KernelLogger log) {
        this.log = log;
    }

    /**
     * Registers a reloadable component in reload order.
     *
     * @param component the component
     */
    public void register(final Reloadable component) {
        components.add(component);
    }

    /**
     * Reloads every registered component in order.
     *
     * @return the aggregate result
     */
    public Result reloadAll() {
        int ok = 0;
        int failed = 0;
        for (final Reloadable component : components) {
            try {
                log.trace("reload", "reloading '%s'", component.reloadTag());
                component.reload();
                ok++;
            } catch (final Throwable t) {
                failed++;
                log.error("reload", t, "component '%s' failed to reload; prior state retained",
                        component.reloadTag());
            }
        }
        log.info("reload", "reload complete: %d ok, %d failed", ok, failed);
        return new Result(ok, failed);
    }
}