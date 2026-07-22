package org.synergyst.minutiae.notify;

import net.kyori.adventure.text.Component;
import org.synergyst.minutiae.message.Arg;
import org.synergyst.minutiae.message.MessageKey;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.synergyst.minutiae.async.AsyncScheduler;
import org.synergyst.minutiae.lifecycle.LifecycleComponent;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.message.MessageService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders and dispatches sanction notifications to configured channels.
 *
 * <p>A dispatch is expressed as a message key and its placeholder resolvers,
 * deferring rendering to each channel so that recipient-specific localisation is
 * preserved. STAFF broadcasts render per online recipient in that recipient's
 * locale; CONSOLE renders in the default locale for the console sender; LOG and
 * WEBHOOK render in the default locale and serialise to plain text.
 *
 * <p>Threading is observed per channel. STAFF and CONSOLE delivery touches
 * entities and the console sender and is marshalled to the main thread. LOG
 * writes are thread-agnostic. WEBHOOK posts are performed on the asynchronous
 * scheduler using a shared, timeout-bounded HTTP client; a delivery failure is
 * logged and never propagated. References to undefined channels are reported
 * once per channel name and thereafter ignored.
 */
public final class NotificationService implements LifecycleComponent {

    private final KernelLogger log;
    private final MessageService messages;
    private final AsyncScheduler scheduler;
    private final NotifyConfig config;
    private final JavaPlugin plugin;

    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
    private final Set<String> warnedUnknown = ConcurrentHashMap.newKeySet();
    private HttpClient httpClient;

    public NotificationService(final KernelLogger log,
                               final MessageService messages,
                               final AsyncScheduler scheduler,
                               final NotifyConfig config,
                               final JavaPlugin plugin) {
        this.log = log;
        this.messages = messages;
        this.scheduler = scheduler;
        this.config = config;
        this.plugin = plugin;
    }

    @Override
    public String tag() {
        return "notify";
    }

    @Override
    public void boot() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        log.info("notify", "service online: %d channel(s), default=%s",
                config.channels().size(), config.defaultChannels());
    }

    @Override
    public void shutdown() {
        this.httpClient = null;
    }

    /**
     * Dispatches a notification to the named channels, falling back to the
     * configured default channels when the supplied collection is empty.
     *
     * @param channelNames lowercased channel names, or empty to use defaults
     * @param key          the message key of the notification template
     * @param resolvers    placeholder resolvers for the template
     */
    public void dispatch(final Collection<String> channelNames,
                         final MessageKey key,
                         final Arg... args) {
        final Collection<String> targets = channelNames.isEmpty()
                ? config.defaultChannels() : channelNames;
        for (final String name : targets) {
            final NotifyChannel channel = config.channels().get(name);
            if (channel == null) {
                if (warnedUnknown.add(name)) {
                    log.warn("notify", "reference to undefined channel '%s'", name);
                }
                continue;
            }
            deliver(channel, key, args);
        }
    }

    private void deliver(final NotifyChannel channel, final MessageKey key, final Arg... args) {
        switch (channel.type()) {
            case STAFF -> runMain(() -> {
                for (final Player online : plugin.getServer().getOnlinePlayers()) {
                    if (online.hasPermission(channel.permission())) {
                        messages.send(online, key, args);
                    }
                }
            });
            case CONSOLE -> runMain(() ->
                    messages.send(plugin.getServer().getConsoleSender(), key, args));
            case LOG -> log.info("notify", "%s", plainText(key, args));
            case WEBHOOK -> postWebhook(channel.url(), plainText(key, args));
        }
    }

    private String plainText(final MessageKey key, final Arg... args) {
        return plain.serialize(messages.render(null, key, args));
    }

    private void postWebhook(final String url, final String content) {
        scheduler.run(() -> {
            try {
                final HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonContent(content)))
                        .build();
                final HttpResponse<Void> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                final int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    log.warn("notify", "webhook returned HTTP %d", status);
                }
            } catch (final Exception e) {
                log.warn("notify", "webhook delivery failed: %s", e.getMessage());
            }
        });
    }

    private static String jsonContent(final String content) {
        final StringBuilder sb = new StringBuilder(content.length() + 24);
        sb.append("{\"content\":\"");
        for (int i = 0, n = content.length(); i < n; i++) {
            final char c = content.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append("\"}");
        return sb.toString();
    }

    private void runMain(final Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }
}