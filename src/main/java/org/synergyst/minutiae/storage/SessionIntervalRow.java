package org.synergyst.minutiae.storage;

/**
 * A recorded connection interval for an account.
 *
 * <p>An interval spans from {@code loginAt} to {@code logoutAt}, both in epoch
 * milliseconds. A {@code logoutAt} of zero denotes an interval that is still
 * open, that is, a currently-connected session; a consumer treats an open
 * interval's end as the current time. {@code ip} is the remote address string
 * observed at login, retained so that handoff transitions can be constrained to a
 * shared address.
 *
 * @param loginAt  interval start in epoch milliseconds
 * @param logoutAt interval end in epoch milliseconds, or zero when open
 * @param ip       remote address string at login, or null when unknown
 */
public record SessionIntervalRow(long loginAt, long logoutAt, String ip) {
}