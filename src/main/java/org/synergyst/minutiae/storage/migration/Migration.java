package org.synergyst.minutiae.storage.migration;

/**
 * A single forward-only schema revision.
 *
 * @param version     strictly increasing revision number, starting at 1
 * @param description short human-readable summary for diagnostics
 * @param statements  ordered DDL/DML statements applied atomically within the
 *                    migration transaction
 */
public record Migration(int version, String description, String[] statements) {

    public Migration {
        if (version < 1) {
            throw new IllegalArgumentException("migration version must be >= 1");
        }
        if (statements == null || statements.length == 0) {
            throw new IllegalArgumentException("migration must contain at least one statement");
        }
    }
}