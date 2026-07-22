package org.synergyst.minutiae.web.hive;

import java.util.concurrent.CompletableFuture;

/**
 * A resolver for one mounted subtree of the Hive namespace.
 *
 * <p>A provider is mounted under a first-segment prefix and receives the full
 * path to resolve, permitting it to route across its own subtree. Resolution is
 * asynchronous, since it is backed by the asynchronous storage layer. A path
 * that does not correspond to any key resolves to null, which the transport
 * surfaces as a not-found response.
 */
@FunctionalInterface
public interface HiveProvider {

    /**
     * Resolves a path to a key.
     *
     * @param path the full path
     * @return a stage yielding the key, or null when the path is unknown
     */
    CompletableFuture<HiveKey> resolve(HivePath path);
}