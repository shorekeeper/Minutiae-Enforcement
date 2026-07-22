package org.synergyst.minutiae.storage;

/**
 * Projection of one active sanction's behavioural contribution, used to
 * reconstruct a player's in-memory behavioural state on join.
 *
 * <p>An {@code expiresAt} of zero denotes a permanent constraint. The mask is
 * the union of behaviours derived from the sanction's measure and annotations at
 * the time it was issued.
 *
 * @param behaviourMask constraint membership mask
 * @param expiresAt     expiry in epoch milliseconds, or zero for permanent
 * @param reason        display reason, possibly null
 */
public record BehaviourRow(long behaviourMask, long expiresAt, String reason) {
}