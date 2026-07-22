package org.synergyst.minutiae.storage;

/**
 * Read projection of a single appeal.
 *
 * <p>{@code status} is one of {@code PENDING}, {@code ACCEPTED}, {@code DENIED}.
 * {@code decidedAt} of zero denotes an appeal awaiting review; any other value
 * is the epoch-millisecond decision timestamp, with {@code reviewer} naming the
 * deciding staff member and {@code verdict} carrying the optional reason.
 *
 * @param id        appeal identifier
 * @param banId     appealed sanction identifier
 * @param appellant name of the submitting player
 * @param text      appeal body
 * @param status    lifecycle status
 * @param verdict   decision reason, or null
 * @param reviewer  deciding staff name, or null
 * @param createdAt submission timestamp
 * @param decidedAt decision timestamp, or zero
 */
public record AppealView(long id,
                         long banId,
                         String appellant,
                         String text,
                         String status,
                         String verdict,
                         String reviewer,
                         long createdAt,
                         long decidedAt) {
}