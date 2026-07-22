package org.synergyst.minutiae.notify;

/**
 * Immutable definition of a single notification channel.
 *
 * <p>Fields not applicable to a channel's type are null: {@code permission} is
 * meaningful only for {@link ChannelType#STAFF}, and {@code url} only for
 * {@link ChannelType#WEBHOOK}.
 *
 * @param name       channel name as referenced by {@code @notify}
 * @param type       delivery mechanism
 * @param permission permission gating a STAFF broadcast, or null
 * @param url        endpoint for a WEBHOOK channel, or null
 */
public record NotifyChannel(String name, ChannelType type, String permission, String url) {
}