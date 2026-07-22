package org.synergyst.minutiae.lifecycle;

import org.synergyst.minutiae.log.KernelLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal ordered service registry with deterministic lifecycle management.
 *
 * <p>This container performs no reflection and no classpath scanning. Services
 * are registered explicitly by type token and retrieved by the same token.
 * Registration order defines boot order; teardown order is its exact reverse.
 *
 * <p>The container is single-threaded by design: all mutating operations are
 * expected to run on the server main thread during enable and disable. No
 * synchronisation is applied to the internal collections.
 */
public final class ServiceContainer {

    private final KernelLogger log;
    private final Map<Class<?>, Object> registry = new HashMap<>(32);
    private final List<LifecycleComponent> booted = new ArrayList<>(16);

    public ServiceContainer(final KernelLogger log) {
        this.log = log;
    }

    /**
     * Registers a plain service instance under the given type token without
     * lifecycle participation.
     *
     * @param type     type token; must not be null
     * @param instance service instance; must not be null
     * @param <T>      service type
     * @return the registered instance for call chaining
     */
    public <T> T register(final Class<T> type, final T instance) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(instance, "instance");
        if (registry.putIfAbsent(type, instance) != null) {
            throw new IllegalStateException("Duplicate service registration: " + type.getName());
        }
        return instance;
    }

    /**
     * Registers a lifecycle-managed component and immediately boots it. On boot
     * failure the component is not retained and the exception is propagated.
     *
     * @param type      type token under which the component is retrievable
     * @param component the component to register and boot
     * @param <T>       component type
     * @return the booted component
     * @throws Exception propagated from {@link LifecycleComponent#boot()}
     */
    public <T extends LifecycleComponent> T bootComponent(final Class<T> type, final T component) throws Exception {
        register(type, component);
        log.trace("service", "booting component '%s' (%s)", component.tag(), type.getSimpleName());
        component.boot();
        booted.add(component);
        log.trace("service", "component '%s' online", component.tag());
        return component;
    }

    /**
     * Resolves a previously registered service.
     *
     * @param type type token
     * @param <T>  service type
     * @return the registered instance
     * @throws IllegalStateException if no service is registered under the token
     */
    public <T> T get(final Class<T> type) {
        final Object instance = registry.get(type);
        if (instance == null) {
            throw new IllegalStateException("Unresolved service: " + type.getName());
        }
        return type.cast(instance);
    }

    /**
     * Shuts down every booted component in reverse boot order. Individual
     * failures are logged and do not interrupt the teardown of remaining
     * components. The registry is cleared on completion.
     */
    public void shutdownAll() {
        for (int i = booted.size() - 1; i >= 0; i--) {
            final LifecycleComponent component = booted.get(i);
            try {
                log.trace("service", "stopping component '%s'", component.tag());
                component.shutdown();
            } catch (final Throwable t) {
                log.error("service", t, "component '%s' failed during shutdown", component.tag());
            }
        }
        booted.clear();
        registry.clear();
    }
}