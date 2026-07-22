package org.synergyst.minutiae.storage;

/**
 * Persistence projection of a sanction, mapping directly onto the {@code bans}
 * table. Timestamps are epoch milliseconds; {@code expiresAt} of zero denotes no
 * expiry. {@code active}, {@code stayed}, {@code provisional},
 * {@code appealable}, and {@code suspended} are 0/1 flags. {@code decayAt} of
 * zero denotes a sanction that never decays out of precedent. {@code parentId}
 * of zero denotes a sanction that is not woven under a parent case.
 * {@code probationUntil} of zero denotes a sanction that opens no probation
 * window. {@code suspendUntil} bounds the recidivism window of a suspended
 * sentence. {@code expungeAt} of zero denotes a sanction never removed from
 * docket visibility. {@code reviewAt} of zero denotes a sanction with no
 * scheduled review.
 *
 * @param uuid           target player UUID as a string
 * @param layout         originating layout key, or null for a manual sanction
 * @param rule           primary rule identifier, or null
 * @param reason         resolved reason text, or null
 * @param issuedAt       issue timestamp, possibly backdated
 * @param expiresAt      expiry timestamp, or zero for none
 * @param staff          attributed staff name
 * @param annotations    serialised annotation display string
 * @param active         active flag
 * @param measure        measure name
 * @param stayed         stay flag
 * @param stayUntil      stay expiry timestamp, or zero
 * @param link           external reference, or null
 * @param behaviourMask  behavioural constraint mask
 * @param provisional    provisional (pre-decision) flag
 * @param decayAt        precedent-decay timestamp, or zero for never
 * @param appealable     appealability flag
 * @param parentId       joinder parent sanction identifier, or zero
 * @param probationUntil probation-window expiry timestamp, or zero
 * @param subjectName    subject display name captured at issue time
 * @param serverId       issuing server identifier, empty for standalone
 * @param suspended      suspended-sentence flag
 * @param suspendUntil   recidivism-window expiry timestamp, or zero
 * @param expungeAt      docket-expungement timestamp, or zero for never
 * @param reviewAt       scheduled-review timestamp, or zero for none
 */
public record SanctionRow(String uuid,
                          String layout,
                          String rule,
                          String reason,
                          long issuedAt,
                          long expiresAt,
                          String staff,
                          String annotations,
                          int active,
                          String measure,
                          int stayed,
                          long stayUntil,
                          String link,
                          long behaviourMask,
                          int provisional,
                          long decayAt,
                          int appealable,
                          long parentId,
                          long probationUntil,
                          String subjectName,
                          String serverId,
                          int suspended,
                          long suspendUntil,
                          long expungeAt,
                          long reviewAt) {
}