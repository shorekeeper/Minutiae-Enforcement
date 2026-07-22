package org.synergyst.minutiae.lifecycle;

/**
 * Contract for managed subsystems with an explicit two-phase lifecycle.
 *
 * <p>Components are booted in registration order and shut down in strict
 * reverse order. A component must not assume the availability of any peer that
 * was registered after it. Both lifecycle methods are invoked exactly once per
 * server session on the thread that owns {@link ServiceContainer}.
 */
public interface LifecycleComponent {

    /**
     * Human-readable identifier used in diagnostic output. Should be stable,
     * short, and free of whitespace.
     *
     * @return the component tag
     */
    String tag();

    /**
     * Acquires resources and transitions the component to a serviceable state.
     *
     * @throws Exception if initialisation fails; the container aborts the boot
     *                   sequence and unwinds already-booted components
     */
    void boot() throws Exception;

    /**
     * Releases all resources acquired during {@link #boot()}. Implementations
     * must be idempotent and must not throw; failures are logged and swallowed
     * by the container to guarantee full teardown.
     */
    void shutdown();
}