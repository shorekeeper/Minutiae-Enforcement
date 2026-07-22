package org.synergyst.minutiae.web.hive;

import org.synergyst.minutiae.lifecycle.LifecycleComponent;
import org.synergyst.minutiae.log.KernelLogger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Federated resolver over the Hive namespace.
 *
 * <p>The Hive is a virtual filesystem of read projections modelled on a registry.
 * The root enumerates the hives; each hive is mounted under its {@code HKEY_}
 * name and resolves its own subtree by path descent. Resolution routes the root
 * to the root provider and any other path to the provider mounted under its first
 * segment; an unmounted prefix resolves to null.
 *
 * <p>The Hive holds no mutable state after boot and is safe for concurrent
 * resolution.
 */
public final class Hive implements LifecycleComponent {

    private final KernelLogger log;
    private final HiveProviders providers;
    private final Map<String, HiveProvider> mounts = new HashMap<>();
    private HiveProvider root;

    public Hive(final KernelLogger log, final HiveProviders providers) {
        this.log = log;
        this.providers = providers;
    }

    @Override
    public String tag() {
        return "hive";
    }

    @Override
    public void boot() {
        this.root = providers::root;
        mounts.put(HiveNames.CASES, providers::cases);
        mounts.put(HiveNames.APPEALS, providers::appeals);
        mounts.put(HiveNames.AMENDS, providers::amends);
        mounts.put(HiveNames.SYSTEM, providers::system);
        mounts.put(HiveNames.FINGERPRINTS, providers::fingerprints);
        log.info("hive", "namespace mounted: %d hive(s)", mounts.size());
    }

    @Override
    public void shutdown() {
        mounts.clear();
        root = null;
    }

    /**
     * Resolves a path to a key.
     *
     * @param path the path
     * @return a stage yielding the key, or null when unknown
     */
    public CompletableFuture<HiveKey> resolve(final HivePath path) {
        if (path.depth() == 0) {
            return root.resolve(path);
        }
        final HiveProvider provider = mounts.get(path.seg(0));
        if (provider == null) {
            return CompletableFuture.completedFuture(null);
        }
        return provider.resolve(path);
    }
}