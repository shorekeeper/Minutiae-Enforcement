package org.synergyst.minutiae.fingerprint;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.message.Arg;
import org.synergyst.minutiae.storage.EvasionMatch;
import org.synergyst.minutiae.storage.Storage;
import org.synergyst.minutiae.message.MessageKey;
import org.synergyst.minutiae.message.MessageService;
import org.synergyst.minutiae.message.Ph;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Coordinates fingerprint capture and evasion scoring.
 *
 * <p>On login, when detection is enabled and the connection has not already been
 * refused by the access guard, the service builds a probe from the incoming
 * address and name, scores it against recorded signals, and raises an alert when
 * the aggregate weight reaches the configured threshold. Scoring joins the
 * storage query directly on the asynchronous pre-login thread, which is
 * appropriate for an inherently asynchronous event; a query failure is logged
 * and treated as no match, so a storage fault never blocks legitimate logins.
 *
 * <p>For sanction issuance, the service exposes {@link #capture(UUID)}, which
 * snapshots the target's session into weighted signal arrays for persistence.
 * Capture returns an empty result when detection is disabled or no session is
 * recorded.
 *
 * <p>I9 raises alerts only; it applies no automatic sanction on a flag.
 */
public final class FingerprintService implements Listener {

    private static final String ALERT_PERMISSION = "minutiae.fingerprint.alert";

    private final KernelLogger log;
    private final Storage storage;
    private final SessionRegistry sessions;
    private final FingerprintConfig config;
    private final JavaPlugin plugin;

    private final MessageService messages;

    private final FingerprintEngine engine;

    public FingerprintService(final KernelLogger log,
                              final Storage storage,
                              final SessionRegistry sessions,
                              final FingerprintConfig config,
                              final FingerprintEngine engine,
                              final MessageService messages,
                              final JavaPlugin plugin) {
        this.log = log;
        this.storage = storage;
        this.sessions = sessions;
        this.config = config;
        this.engine = engine;
        this.messages = messages;
        this.plugin = plugin;
    }

    /**
     * Scores an incoming login under the calibrated evidence model and raises an
     * alert when the posterior probability of shared identity reaches the model's
     * flag threshold.
     *
     * <p>The extended probe, including the provider label and, when enabled, the
     * reverse-DNS host pattern, is matched against the signals of active
     * connection-blocking sanctions. The resulting agreements are scored per
     * candidate account; the best candidate, if it clears the threshold, is
     * reported. Query and reverse-DNS resolution occur on the asynchronous
     * pre-login thread; a failure degrades to no match, so a storage or resolver
     * fault never blocks a legitimate login.
     *
     * @param event the pre-login event
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        if (!config.enabled()) {
            return;
        }
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        final ProbeArrays probe = SignalCollector.probe(
                event.getAddress(), event.getName(),
                engine.networkClassifier(), engine.reverseDnsEnabled());

        final long now = System.currentTimeMillis();
        final List<MatchingSignalRow> rows;
        try {
            rows = storage.matchingSignals(probe.types(), probe.values(), now).join();
        } catch (final RuntimeException e) {
            log.error("fingerprint", e, "evasion query failed for %s; permitting", event.getName());
            return;
        }

        final EvasionAssessment assessment =
                engine.assessLogin(rows, event.getUniqueId(), now);
        if (assessment == null
                || assessment.evidence().probability() < engine.model().flagThreshold()) {
            return;
        }
        raiseAlert(event.getName(), event.getUniqueId(), event.getAddress(),
                new EvasionMatch(assessment.uuid(), assessment.evidence().probability(),
                        assessment.evidence().contributions().size()));
    }

    /**
     * Snapshots a player's recorded session into weighted signal arrays for
     * persistence with a sanction, over the full signal family.
     *
     * @param uuid the target UUID
     * @return the capture arrays; an empty capture when disabled or no session is
     *         recorded
     */
    public CaptureArrays capture(final UUID uuid) {
        if (!config.enabled()) {
            return new CaptureArrays(new int[0], new String[0], new double[0]);
        }
        final Session session = sessions.get(uuid);
        if (session == null) {
            log.trace("fingerprint", "no session for %s; capturing no signals", uuid);
            return new CaptureArrays(new int[0], new String[0], new double[0]);
        }
        // Capture runs on the asynchronous persist path, so the reverse-DNS
        // lookup carried by the extended capture is off the main thread.
        return SignalCollector.capture(session, config.weights(),
                engine.networkClassifier(), engine.reverseDnsEnabled());
    }

    /**
     * Scores an online player's current connection for diagnostic inspection,
     * projecting the calibrated assessment onto the legacy match shape.
     *
     * @param player the player to score
     * @return a stage yielding the best match, or null when none is found
     */
    public CompletableFuture<EvasionMatch> scorePlayer(final Player player) {
        final InetAddress address = player.getAddress() == null
                ? null : player.getAddress().getAddress();
        final ProbeArrays probe = SignalCollector.probe(
                address, player.getName(), engine.networkClassifier(), false);
        final long now = System.currentTimeMillis();
        return storage.matchingSignals(probe.types(), probe.values(), now).thenApply(rows -> {
            final EvasionAssessment a =
                    engine.assessLogin(rows, player.getUniqueId(), now);
            return a == null ? null : new EvasionMatch(
                    a.uuid(), a.evidence().probability(), a.evidence().contributions().size());
        });
    }

    /** Returns the fingerprint engine. */
    public FingerprintEngine engine() {
        return engine;
    }

    private void raiseAlert(final String name, final UUID uuid, final InetAddress address,
                            final EvasionMatch match) {
        log.warn("fingerprint", "EVASION ALERT: %s (%s, %s) score=%.2f matches banned=%s signals=%d",
                name, uuid, address.getHostAddress(), match.score(),
                match.bannedUuid(), match.matchedSignals());

        final String score = String.format(java.util.Locale.ROOT, "%.2f", match.score());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (final Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission(ALERT_PERMISSION)) {
                    messages.send(online, MessageKey.FINGERPRINT_ALERT,
                            Arg.s("name", name),
                            Arg.s("score", score),
                            Arg.s("banned", match.bannedUuid()));
                }
            }
        });
    }

    /** Returns the active configuration. */
    public FingerprintConfig config() {
        return config;
    }

    /** Returns the session registry. */
    public SessionRegistry sessions() {
        return sessions;
    }
}