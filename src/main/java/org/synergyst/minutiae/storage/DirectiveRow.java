package org.synergyst.minutiae.storage;

/**
 * Read projection of one cross-server directive.
 *
 * <p>{@code target} of null denotes a broadcast addressed to every instance
 * except the origin. Identifiers increase monotonically and define the
 * per-instance consumption order.
 *
 * @param id        directive identifier, monotonically increasing
 * @param origin    publishing server identifier
 * @param target    addressed server identifier, or null for broadcast
 * @param kind      directive kind name
 * @param subject   subject account UUID string, possibly empty
 * @param payload   kind-specific payload, possibly empty
 * @param createdAt publication timestamp in epoch milliseconds
 * @param expiresAt expiry timestamp in epoch milliseconds
 */
public record DirectiveRow(long id, String origin, String target, String kind,
                           String subject, String payload, long createdAt, long expiresAt) {
}