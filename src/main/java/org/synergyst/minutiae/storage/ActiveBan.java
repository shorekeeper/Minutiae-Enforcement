package org.synergyst.minutiae.storage;

/**
 * Projection of a connection-blocking sanction, used by the access guard.
 *
 * <p>An {@code expiresAt} of zero denotes a permanent sanction. The
 * {@code behaviourMask} carries the sanction's behavioural constraints; when it
 * indicates concealment the access guard permits the connection so that the
 * concealment can be applied on join rather than refusing entry outright.
 *
 * @param measure       measure name
 * @param reason        display reason, possibly null
 * @param expiresAt     expiry timestamp, or zero
 * @param behaviourMask behavioural constraint mask
 */
public record ActiveBan(String measure, String reason, long expiresAt, long behaviourMask) {
}