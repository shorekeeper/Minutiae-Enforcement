package org.synergyst.minutiae.storage;

/**
 * Outcome of an amend operation.
 *
 * @param found         whether the target sanction existed
 * @param diff          human-readable summary of the fields that changed
 * @param uuid          target player UUID string, or null when not found
 * @param measure       measure name, unaffected by amend
 * @param expiresAt     post-amend expiry timestamp
 * @param behaviourMask behavioural constraint mask, unaffected by amend
 */
public record AmendResult(boolean found,
                          String diff,
                          String uuid,
                          String measure,
                          long expiresAt,
                          long behaviourMask) {
}