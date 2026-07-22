package org.synergyst.minutiae.storage;

/**
 * Read projection of a single sanction, sufficient to display it and to
 * reconcile its effects when lifted or amended.
 *
 * <p>{@code liftedAt} of zero denotes a sanction that has not been lifted.
 * {@code parentId} of zero denotes a sanction that is not woven under a parent.
 *
 * @param id            sanction identifier
 * @param uuid          target player UUID as a string
 * @param layout        originating layout key, or null
 * @param rule          primary rule identifier, or null
 * @param reason        resolved reason text, or null
 * @param issuedAt      issue timestamp
 * @param expiresAt     expiry timestamp, or zero
 * @param staff         attributed staff name
 * @param measure       measure name
 * @param active        active flag
 * @param stayed        stay flag
 * @param behaviourMask behavioural constraint mask
 * @param liftedAt      lift timestamp, or zero
 * @param liftedBy      lifting actor, or null
 * @param appealable    appealability flag
 * @param parentId      joinder parent sanction identifier, or zero
 * @param liftKind      lift kind code as defined by {@code LiftKind}; zero
 *                      (vacate) for unlifted rows and for lifts predating the
 *                      kind column
 */
public record SanctionView(long id,
                           String uuid,
                           String layout,
                           String rule,
                           String reason,
                           long issuedAt,
                           long expiresAt,
                           String staff,
                           String measure,
                           int active,
                           int stayed,
                           long behaviourMask,
                           long liftedAt,
                           String liftedBy,
                           int appealable,
                           long parentId,
                           int liftKind) {
}