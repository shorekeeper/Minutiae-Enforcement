package org.synergyst.minutiae.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.synergyst.minutiae.lifecycle.LifecycleComponent;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.storage.Storage;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures, bounds, and persists per-player chat transcripts.
 *
 * <p>The service maintains one fixed-capacity {@link ChatRing} per player in a
 * concurrent map. Chat lines are recorded on the asynchronous chat thread at
 * monitor priority; the message component is serialised to plain text, stripped
 * of control and section-sign characters, and truncated to a configured maximum
 * before storage. Nothing recorded is ever interpreted as markup, format code,
 * or template, and no line is logged through a parameterised logger, so the
 * capture path cannot be turned into a formatting or injection vector, and the
 * ring bounds preclude a memory-exhaustion vector from sustained chat.
 *
 * <p>A snapshot of a player's ring is taken by the sanction executor when a
 * sanction requests one, then persisted asynchronously against the generated
 * sanction identifier. Persistence writes plain-text rows; CSV rendering for
 * display is performed on read.
 *
 * <p>Idle rings are reclaimed two ways: on player disconnect the ring's
 * last-activity time is stamped, and an amortised sweep triggered from within
 * {@link #record} evicts rings for departed players past the retention grace and
 * enforces the global tracked-player bound. No external scheduler is required.
 *
 * <p>The backing map is concurrent; per-ring state is confined under a per-ring
 * monitor so a single chat-thread writer never races the dispatch-thread reader.
 */
public final class ChatHistoryService implements LifecycleComponent, Listener {

    private static final int SWEEP_EVERY = 256;
    private static final char SECTION_SIGN = '\u00A7';

    private final KernelLogger log;
    private final Storage storage;
    private final ChatCaptureConfig config;
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
    private final Map<UUID, ChatRing> rings = new ConcurrentHashMap<>(256);
    private int opCounter;

    public ChatHistoryService(final KernelLogger log,
                              final Storage storage,
                              final ChatCaptureConfig config) {
        this.log = log;
        this.storage = storage;
        this.config = config;
    }

    @Override
    public String tag() {
        return "chat-history";
    }

    @Override
    public void boot() {
        log.info("chat-history", "capture %s: mode=%s per-player=%d max-len=%d max-tracked=%d",
                config.enabled() ? "enabled" : "disabled", config.mode(),
                config.perPlayer(), config.maxMessageLength(), config.maxTracked());
    }

    @Override
    public void shutdown() {
        rings.clear();
    }

    /**
     * Records a chat line into the speaker's ring.
     *
     * <p>Runs at monitor priority and does not cancel the event. A message that
     * sanitises to an empty string is discarded.
     *
     * @param event the chat event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        if (!config.enabled()) {
            return;
        }
        final String sanitised = sanitise(plain.serialize(event.message()));
        if (sanitised.isEmpty()) {
            return;
        }
        final Player player = event.getPlayer();
        final long now = System.currentTimeMillis();
        final ChatRing ring = rings.computeIfAbsent(
                player.getUniqueId(), k -> new ChatRing(config.perPlayer()));
        synchronized (ring) {
            ring.record(now, sanitised);
        }
        maybeSweep(now);
    }

    /**
     * Stamps a departing player's ring so the sweep may reclaim it after grace.
     *
     * @param event the quit event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final ChatRing ring = rings.get(event.getPlayer().getUniqueId());
        if (ring != null) {
            synchronized (ring) {
                ring.record(System.currentTimeMillis(), ""); // touch without content
            }
        }
    }

    /**
     * Takes a chronological snapshot of a player's current ring.
     *
     * @param subject the subject account, possibly null
     * @return the snapshot; {@link ChatSnapshot#EMPTY} when disabled, unknown, or
     *         empty
     */
    public ChatSnapshot snapshot(final UUID subject) {
        if (!config.enabled() || subject == null) {
            return ChatSnapshot.EMPTY;
        }
        final ChatRing ring = rings.get(subject);
        if (ring == null) {
            return ChatSnapshot.EMPTY;
        }
        synchronized (ring) {
            return ring.snapshot();
        }
    }

    /**
     * Reports whether a snapshot should be taken for a sanction.
     *
     * @param annotationPresent whether the {@code transcript} annotation is set
     * @param blocksOrBehaviour whether the sanction blocks connection or applies
     *                          a behavioural constraint
     * @return {@code true} when a snapshot should be captured
     */
    public boolean shouldCapture(final boolean annotationPresent, final boolean blocksOrBehaviour) {
        if (!config.enabled()) {
            return false;
        }
        if (annotationPresent) {
            return true;
        }
        return config.mode() == ChatCaptureConfig.Mode.ALWAYS && blocksOrBehaviour;
    }

    /**
     * Persists a snapshot against a sanction identifier.
     *
     * @param banId    the generated sanction identifier
     * @param snapshot the snapshot to persist
     * @return a stage completing when persistence finishes; a completed stage
     *         when the snapshot is empty
     */
    public CompletableFuture<Void> persist(final long banId, final ChatSnapshot snapshot) {
        if (snapshot.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return storage.persistTranscript(banId, snapshot.stamps(), snapshot.bodies());
    }

    // ----------------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------------

    private String sanitise(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        final int cap = config.maxMessageLength();
        final StringBuilder sb = new StringBuilder(Math.min(raw.length(), cap));
        for (int i = 0, n = raw.length(); i < n && sb.length() < cap; i++) {
            final char c = raw.charAt(i);
            if (c == SECTION_SIGN || c == '&') {
                continue;
            }
            if (c < 0x20 && c != '\t') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().strip();
    }

    private void maybeSweep(final long now) {
        if (++opCounter % SWEEP_EVERY != 0) {
            return;
        }
        final long horizon = now - config.graceMillis();
        int removed = 0;
        final Iterator<Map.Entry<UUID, ChatRing>> it = rings.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<UUID, ChatRing> e = it.next();
            if (org.bukkit.Bukkit.getPlayer(e.getKey()) == null
                    && e.getValue().lastActivity() < horizon) {
                it.remove();
                removed++;
            }
        }
        // Global bound: evict oldest-touched rings until within the cap.
        while (rings.size() > config.maxTracked()) {
            UUID oldest = null;
            long oldestTs = Long.MAX_VALUE;
            for (final Map.Entry<UUID, ChatRing> e : rings.entrySet()) {
                final long ts = e.getValue().lastActivity();
                if (ts < oldestTs) {
                    oldestTs = ts;
                    oldest = e.getKey();
                }
            }
            if (oldest == null) {
                break;
            }
            rings.remove(oldest);
            removed++;
        }
        if (removed > 0) {
            log.trace("chat-history", "swept %d ring(s); tracking %d", removed, rings.size());
        }
    }
}