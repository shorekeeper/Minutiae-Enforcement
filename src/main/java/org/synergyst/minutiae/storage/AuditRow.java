package org.synergyst.minutiae.storage;

/**
 * Read projection of a single audit entry.
 *
 * @param id     audit identifier
 * @param banId  associated sanction identifier, or null
 * @param action action code
 * @param actor  acting authority
 * @param ts     entry timestamp in epoch milliseconds
 * @param detail free-form detail, or null
 */
public record AuditRow(long id, Long banId, String action, String actor, long ts, String detail) {
}