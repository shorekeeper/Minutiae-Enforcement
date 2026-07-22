package org.synergyst.minutiae.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Top-level runtime configuration snapshot.
 *
 * <p>A snapshot is materialised once from the backing {@link FileConfiguration}
 * and is thereafter immutable. Consumers hold the record directly; no live view
 * of the underlying file is exposed.
 *
 * @param storage     storage subsystem configuration
 * @param bootVerbose whether fine-grained boot diagnostics are emitted
 */
public record RuntimeConfig(StorageConfig storage,
                            String rulesFile,
                            String layoutsFile,
                            boolean bootVerbose) {

    public static RuntimeConfig from(final FileConfiguration cfg) {
        final String driver = cfg.getString("storage.driver", "SQLITE").trim().toUpperCase();
        final String sqliteFile = cfg.getString("storage.sqlite.file", "minutiae.db").trim();
        final int maxConnections = cfg.getInt("storage.pool.max-connections", 4);
        final long acquireTimeoutMs = cfg.getLong("storage.pool.acquire-timeout-ms", 5000L);
        final long busyTimeoutMs = cfg.getLong("storage.pool.busy-timeout-ms", 5000L);
        final String rulesFile = cfg.getString("rules.file", "rules.yml").trim();
        final String layoutsFile = cfg.getString("layouts.file", "layouts.yml").trim();
        final boolean verbose = cfg.getBoolean("boot.verbose", true);

        final StorageConfig storage = new StorageConfig(
                driver, sqliteFile, maxConnections, acquireTimeoutMs, busyTimeoutMs);
        return new RuntimeConfig(storage, rulesFile, layoutsFile, verbose);
    }
}