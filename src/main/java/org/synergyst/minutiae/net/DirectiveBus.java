package org.synergyst.minutiae.net;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.synergyst.minutiae.behaviour.BehaviourEffects;
import org.synergyst.minutiae.behaviour.BehaviourManager;
import org.synergyst.minutiae.lifecycle.LifecycleComponent;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.message.Arg;
import org.synergyst.minutiae.message.MessageKey;
import org.synergyst.minutiae.message.MessageService;
import org.synergyst.minutiae.storage.BehaviourRow;
import org.synergyst.minutiae.storage.DirectiveRow;
import org.synergyst.minutiae.storage.Storage;

import java.util.List;
import java.util.UUID;

/**
 * Storage-backed directive bus: publication and polling consumption.
 *
 * <p>Publication inserts a broadcast row addressed to every instance except
 * the origin. Consumption runs on a fixed asynchronous schedule: the poller
 * reads the batch above the persistent cursor, marshals application of each
 * directive to the main thread, then persists the advanced cursor. The
 * cursor is persisted after scheduling application rather than after
 * completion; the resulting at-most-one-batch replay window on crash is
 * covered by handler idempotency.
 *
 * <p>Application handlers:
 * <ul>
 *   <li>{@code KICK} - kicks the subject when online here, rendering the
 *       screen in the subject's locale. An offline subject is a no-op: the
 *       access gate already blocks reconnection network-wide through the
 *       shared backend.</li>
 *   <li>{@code SYNC_BEHAVIOUR} - when the subject is online here, discards
 *       the in-memory behavioural record, refolds it from the active rows of
 *       the shared backend, and reconciles concealment. The handler mirrors
 *       the join-time reconciliation path, so a directive and a rejoin
 *       converge on identical state.</li>
 * </ul>
 *
 * <p>Expired directives are purged by an amortised sweep from within the
 * poll loop. A first boot with no persisted cursor initialises at the
 * current maximum identifier: history predating the instance is not
 * replayed, because join-time reconciliation derives it from the
 * authoritative tables anyway.
 */
public final class DirectiveBus implements LifecycleComponent, NetworkBus {

    private static final int BATCH_LIMIT = 64;
    private static final int PURGE_EVERY = 30;

    private final KernelLogger log;
    private final JavaPlugin plugin;
    private final NetworkConfig config;
    private final ServerIdentity identity;
    private final Storage storage;
    private final MessageService messages;
    private final BehaviourManager behaviourManager;
    private final BehaviourEffects behaviourEffects;

    private BukkitTask task;
    private volatile long cursor = -1L;
    private int pollCounter;

    public DirectiveBus(final KernelLogger log,
                        final JavaPlugin plugin,
                        final NetworkConfig config,
                        final ServerIdentity identity,
                        final Storage storage,
                        final MessageService messages,
                        final BehaviourManager behaviourManager,
                        final BehaviourEffects behaviourEffects) {
        this.log = log;
        this.plugin = plugin;
        this.config = config;
        this.identity = identity;
        this.storage = storage;
        this.messages = messages;
        this.behaviourManager = behaviourManager;
        this.behaviourEffects = behaviourEffects;
    }

    @Override
    public String tag() {
        return "network";
    }

    @Override
    public void boot() {
        if (!config.enabled()) {
            log.info("network", "disabled; directive bus idle (standalone mode)");
            return;
        }
        final long stored = storage.directiveCursor(identity.id()).join();
        this.cursor = stored >= 0L ? stored : storage.maxDirectiveId().join();

        final long ticks = Math.max(5L, config.pollIntervalMs() / 50L);
        this.task = plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, this::poll, ticks, ticks);
        log.info("network", "bus online: server-id=%s poll=%dms ttl=%dms cursor=%d",
                identity.id(), config.pollIntervalMs(), config.directiveTtlMs(), cursor);
    }

    @Override
    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
    }

    // ------------------------------------------------------------------
    // Publication
    // ------------------------------------------------------------------

    @Override
    public void publishKick(final UUID subject, final String reason) {
        if (!config.enabled() || subject == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        storage.publishDirective(identity.id(), null, DirectiveKind.KICK.name(),
                        subject.toString(), reason == null ? "" : reason,
                        now, config.directiveTtlMs())
                .exceptionally(err -> {
                    log.warn("network", "kick directive publish failed: %s", err.getMessage());
                    return -1L;
                });
    }

    @Override
    public void publishBehaviourSync(final UUID subject) {
        if (!config.enabled() || subject == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        storage.publishDirective(identity.id(), null, DirectiveKind.SYNC_BEHAVIOUR.name(),
                        subject.toString(), "", now, config.directiveTtlMs())
                .exceptionally(err -> {
                    log.warn("network", "sync directive publish failed: %s", err.getMessage());
                    return -1L;
                });
    }

    // ------------------------------------------------------------------
    // Consumption
    // ------------------------------------------------------------------

    private void poll() {
        final long now = System.currentTimeMillis();
        final List<DirectiveRow> batch;
        try {
            batch = storage.pollDirectives(identity.id(), cursor, now, BATCH_LIMIT).join();
        } catch (final RuntimeException e) {
            log.warn("network", "directive poll failed: %s", e.getMessage());
            return;
        }
        if (!batch.isEmpty()) {
            for (final DirectiveRow row : batch) {
                plugin.getServer().getScheduler().runTask(plugin, () -> apply(row));
            }
            cursor = batch.get(batch.size() - 1).id();
            storage.saveDirectiveCursor(identity.id(), cursor)
                    .exceptionally(err -> {
                        log.warn("network", "cursor persist failed: %s", err.getMessage());
                        return null;
                    });
        }
        if (++pollCounter % PURGE_EVERY == 0) {
            storage.purgeDirectives(now).exceptionally(err -> 0);
        }
    }

    private void apply(final DirectiveRow row) {
        final DirectiveKind kind = DirectiveKind.parse(row.kind());
        if (kind == null) {
            log.trace("network", "unrecognised directive kind '%s' skipped", row.kind());
            return;
        }
        final UUID subject = parseUuid(row.subject());
        if (subject == null) {
            return;
        }
        switch (kind) {
            case KICK -> applyKick(subject, row.payload());
            case SYNC_BEHAVIOUR -> applySync(subject);
        }
    }

    private void applyKick(final UUID subject, final String reason) {
        final Player online = plugin.getServer().getPlayer(subject);
        if (online == null) {
            return;
        }
        final String tag = messages.localeTagFor(online);
        online.kick(messages.render(tag, MessageKey.MECHANISM_KICK,
                Arg.s("reason", reason == null || reason.isEmpty() ? "-" : reason)));
        log.info("network", "remote kick applied to %s", online.getName());
    }

    private void applySync(final UUID subject) {
        final Player online = plugin.getServer().getPlayer(subject);
        if (online == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        storage.activeBehaviours(subject.toString(), now).whenComplete((rows, err) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (err != null) {
                        log.warn("network", "behaviour sync failed for %s: %s",
                                online.getName(), err.getMessage());
                        return;
                    }
                    behaviourManager.remove(subject);
                    for (final BehaviourRow row : rows) {
                        behaviourManager.apply(subject, row.behaviourMask(),
                                row.expiresAt(), row.reason());
                    }
                    behaviourEffects.reconcileConcealment(online, System.currentTimeMillis());
                    log.trace("network", "behaviour synced for %s (%d row(s))",
                            online.getName(), rows.size());
                }));
    }

    private static UUID parseUuid(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }
}