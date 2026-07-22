package org.synergyst.minutiae.config;

/**
 * Immutable storage subsystem configuration.
 *
 * @param driver          backend selector; the only currently serviceable
 *                        value is {@code SQLITE}
 * @param sqliteFile      database file path as declared in configuration; may
 *                        be relative to the plugin data folder or absolute
 * @param maxConnections  upper bound on physical connections held by the pool;
 *                        must be at least 1
 * @param acquireTimeoutMs connection borrow timeout in milliseconds
 * @param busyTimeoutMs   SQLite {@code busy_timeout} applied per connection
 */
public record StorageConfig(
        String driver,
        String sqliteFile,
        int maxConnections,
        long acquireTimeoutMs,
        long busyTimeoutMs
) {
    public StorageConfig {
        if (maxConnections < 1) {
            throw new IllegalArgumentException("storage.pool.max-connections must be >= 1");
        }
        if (acquireTimeoutMs < 1) {
            throw new IllegalArgumentException("storage.pool.acquire-timeout-ms must be >= 1");
        }
        if (busyTimeoutMs < 0) {
            throw new IllegalArgumentException("storage.pool.busy-timeout-ms must be >= 0");
        }
    }
}