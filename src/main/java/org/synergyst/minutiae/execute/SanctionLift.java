package org.synergyst.minutiae.execute;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.synergyst.minutiae.behaviour.Behaviour;
import org.synergyst.minutiae.behaviour.BehaviourEffects;
import org.synergyst.minutiae.behaviour.BehaviourManager;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.measure.Measure;
import org.synergyst.minutiae.message.Arg;
import org.synergyst.minutiae.message.MessageKey;
import org.synergyst.minutiae.message.MessageService;
import org.synergyst.minutiae.net.NetworkBus;
import org.synergyst.minutiae.notify.NotificationService;
import org.synergyst.minutiae.rank.RankConfig;
import org.synergyst.minutiae.storage.BehaviourRow;
import org.synergyst.minutiae.storage.SanctionView;
import org.synergyst.minutiae.storage.Storage;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Lifts, cascades, bulk-lifts, and reinstates sanctions, reversing or
 * restoring their in-session effects.
 *
 * <p>A single lift resolves the target sanction, verifies it has not already
 * been lifted, enforces the target-dependent permission gates, clears its
 * active and stayed flags while stamping lift metadata (kind, actor, reason,
 * optional probation window), and appends an audit entry. When the target is
 * online, the player's behavioural state is recomputed from their remaining
 * active sanctions and any concealment is reversed, so that lifting one of
 * several overlapping sanctions removes only that sanction's contribution.
 *
 * <p>Two permission gates depend on the loaded sanction and are enforced
 * here rather than in the resolver: lifting a sanction issued by another
 * staff member requires the foreign-lift node, and lifting a
 * connection-blocking sanction requires the blocking-lift node. Both gates
 * apply to every entry path, including appeal acceptance and the web panel.
 *
 * <p>A cascade lift extends the operation over the whole joinder chain of the
 * target. Chain members are lifted under the root's kind and reason; the
 * target-dependent gates are evaluated on the root only, since the cascade
 * grant itself is the senior authority. Cascade persistence runs on the
 * asynchronous continuation of the chain query, joining per-member storage
 * stages; the scheduler is virtual-thread-per-task, so a join never starves a
 * carrier thread.
 *
 * <p>A bulk lift removes every unlifted, effective sanction matching a rule
 * or staff criterion in one transaction, always under the vacate kind. The
 * command surface requires an explicit preview-then-confirm sequence.
 * Behavioural reconciliation after a bulk lift covers players online on this
 * instance; subjects online elsewhere reconcile on their next join, which is
 * the accepted bound of a criterion-wide operation whose affected subjects
 * are not enumerated.
 *
 * <p>An unlift reverses an erroneous lift: the lift metadata is cleared, the
 * active flag is recomputed from the sanction's temporal state, behavioural
 * constraints are re-applied to an online subject, and a restored
 * connection-blocking sanction removes an online, non-shadowed subject from
 * the server.
 *
 * <p>All storage access runs on the asynchronous scheduler; every
 * player-facing and entity-touching action is marshalled to the main thread.
 */
public final class SanctionLift {

    private final KernelLogger log;
    private final Storage storage;
    private final MessageService messages;
    private final BehaviourManager behaviourManager;
    private final BehaviourEffects behaviourEffects;
    private final BroadcastConfig broadcast;
    private final RankConfig ranks;
    private final NetworkBus network;
    private final NotificationService notifications;
    private final JavaPlugin plugin;

    /** Permission node allowing a lift of another staff member's sanction. */
    public static final String PERMISSION_FOREIGN = "minutiae.lift.foreign";

    /** Permission node allowing a lift of a connection-blocking sanction. */
    public static final String PERMISSION_BLOCKING = "minutiae.lift.blocking";

    public SanctionLift(final KernelLogger log,
                        final Storage storage,
                        final MessageService messages,
                        final BehaviourManager behaviourManager,
                        final BehaviourEffects behaviourEffects,
                        final BroadcastConfig broadcast,
                        final RankConfig ranks,
                        final NetworkBus network,
                        final NotificationService notifications,
                        final JavaPlugin plugin) {
        this.log = log;
        this.storage = storage;
        this.messages = messages;
        this.behaviourManager = behaviourManager;
        this.behaviourEffects = behaviourEffects;
        this.broadcast = broadcast;
        this.ranks = ranks;
        this.network = network;
        this.notifications = notifications;
        this.plugin = plugin;
    }

    // ----------------------------------------------------------------------
    // Entry points
    // ----------------------------------------------------------------------

    /**
     * Lifts a sanction under a fully resolved description.
     *
     * @param actor the command sender performing the lift
     * @param id    the sanction identifier
     * @param spec  the resolved lift description
     */
    public void lift(final CommandSender actor, final long id, final ResolvedLift spec) {
        final String rankId = ranks.resolve(actor);
        final long now = System.currentTimeMillis();
        storage.findSanction(id).thenAccept(view -> {
            if (view == null) {
                runMain(() -> messages.send(actor, MessageKey.LIFT_NOT_FOUND, Arg.n("id", id)));
                return;
            }
            if (view.liftedAt() != 0L) {
                runMain(() -> messages.send(actor, MessageKey.LIFT_ALREADY, Arg.n("id", id)));
                return;
            }
            if (!gate(actor, view)) {
                return;
            }
            if (spec.dryRun()) {
                preview(actor, view, spec);
                return;
            }
            if (spec.cascade()) {
                cascade(actor, rankId, view, spec, now);
                return;
            }
            single(actor, rankId, view, spec, now, true);
        }).exceptionally(err -> {
            runMain(() -> messages.send(actor, MessageKey.ERROR_ENFORCE_FAILED,
                    Arg.s("error", rootMessage(err))));
            return null;
        });
    }

    /**
     * Lifts a sanction under a kind with a plain reason and no modifiers.
     * Serves callers carrying no operator token stream: appeal acceptance and
     * the web panel.
     *
     * @param actor  the command sender performing the lift
     * @param id     the sanction identifier
     * @param kind   the lift kind
     * @param reason optional lift reason, or null
     */
    public void lift(final CommandSender actor, final long id,
                     final LiftKind kind, final String reason) {
        lift(actor, id, ResolvedLift.plain(kind).withReason(reason));
    }

    /**
     * Lifts a sanction under the vacate kind, the historical default.
     *
     * @param actor  the command sender performing the lift
     * @param id     the sanction identifier
     * @param reason optional lift reason, or null
     */
    public void lift(final CommandSender actor, final long id, final String reason) {
        lift(actor, id, LiftKind.VACATE, reason);
    }

    /**
     * Reports how many sanctions a bulk lift would affect.
     *
     * @param actor     the requesting sender
     * @param criterion the criterion column
     * @param value     the matched value
     */
    public void previewBulk(final CommandSender actor, final Storage.BulkCriterion criterion,
                            final String value) {
        storage.countLiftable(criterion, value).thenAccept(count -> runMain(() -> {
            if (count == 0) {
                messages.send(actor, MessageKey.LIFT_BULK_EMPTY, Arg.s("value", value));
            } else {
                messages.send(actor, MessageKey.LIFT_BULK_PREVIEW,
                        Arg.n("count", count), Arg.s("value", value));
            }
        }));
    }

    /**
     * Executes a bulk lift under the vacate kind and reconciles every player
     * online on this instance.
     *
     * @param actor     the executing sender
     * @param criterion the criterion column
     * @param value     the matched value
     * @param reason    optional lift reason, or null
     */
    public void bulkLift(final CommandSender actor, final Storage.BulkCriterion criterion,
                         final String value, final String reason) {
        final long now = System.currentTimeMillis();
        storage.bulkLift(criterion, value, actor.getName(), now, reason)
                .whenComplete((count, err) -> runMain(() -> {
                    if (err != null) {
                        messages.send(actor, MessageKey.ERROR_ENFORCE_FAILED,
                                Arg.s("error", rootMessage(err)));
                        log.error("lift", err, "bulk lift failed for %s=%s",
                                criterion, value);
                        return;
                    }
                    if (count == 0) {
                        messages.send(actor, MessageKey.LIFT_BULK_EMPTY, Arg.s("value", value));
                        return;
                    }
                    for (final Player online : plugin.getServer().getOnlinePlayers()) {
                        reconcileUuid(online.getUniqueId(), now, false);
                    }
                    messages.send(actor, MessageKey.LIFT_BULK_DONE,
                            Arg.n("count", count), Arg.s("value", value));
                    log.info("lift", "bulk lift %s=%s removed %d sanction(s), by %s",
                            criterion, value, count, actor.getName());
                }));
    }

    /**
     * Reverses an erroneous lift.
     *
     * @param actor  the reinstating sender
     * @param id     the sanction identifier
     * @param reason optional reinstatement reason, or null
     */
    public void unlift(final CommandSender actor, final long id, final String reason) {
        final long now = System.currentTimeMillis();
        storage.findSanction(id).thenAccept(view -> {
            if (view == null) {
                runMain(() -> messages.send(actor, MessageKey.LIFT_NOT_FOUND, Arg.n("id", id)));
                return;
            }
            if (view.liftedAt() == 0L) {
                runMain(() -> messages.send(actor, MessageKey.UNLIFT_NOT_LIFTED, Arg.n("id", id)));
                return;
            }
            storage.unliftSanction(id, actor.getName(), now, reason)
                    .whenComplete((rows, err) -> runMain(() -> {
                        if (err != null) {
                            messages.send(actor, MessageKey.ERROR_ENFORCE_FAILED,
                                    Arg.s("error", rootMessage(err)));
                            log.error("lift", err, "unlift failed for sanction #%d", id);
                            return;
                        }
                        if (rows == 0) {
                            messages.send(actor, MessageKey.UNLIFT_NOT_LIFTED, Arg.n("id", id));
                            return;
                        }
                        applyReinstated(view, now);
                        network.publishBehaviourSync(UUID.fromString(view.uuid()));
                        messages.send(actor, MessageKey.UNLIFT_SUCCESS,
                                Arg.n("id", id), Arg.s("target", nameOf(view.uuid())));
                        log.info("lift", "#%d reinstated by %s", id, actor.getName());
                    }));
        }).exceptionally(err -> {
            runMain(() -> messages.send(actor, MessageKey.ERROR_ENFORCE_FAILED,
                    Arg.s("error", rootMessage(err))));
            return null;
        });
    }

    // ----------------------------------------------------------------------
    // Gates and preview
    // ----------------------------------------------------------------------

    /**
     * Enforces the target-dependent gates: foreign issuer and blocking
     * measure. A failing gate reports to the actor and returns false.
     *
     * @param actor the lifting sender
     * @param view  the loaded sanction
     * @return {@code true} when both gates pass
     */
    private boolean gate(final CommandSender actor, final SanctionView view) {
        if (!view.staff().equals(actor.getName()) && !actor.hasPermission(PERMISSION_FOREIGN)) {
            runMain(() -> messages.send(actor, MessageKey.LIFT_DENY_FOREIGN,
                    Arg.s("staff", view.staff())));
            return false;
        }
        if (blocksConnection(view.measure()) && !actor.hasPermission(PERMISSION_BLOCKING)) {
            runMain(() -> messages.send(actor, MessageKey.LIFT_DENY_BLOCKING,
                    Arg.measure(view.measure())));
            return false;
        }
        return true;
    }

    private void preview(final CommandSender actor, final SanctionView view,
                         final ResolvedLift spec) {
        if (!spec.cascade()) {
            runMain(() -> messages.send(actor, MessageKey.LIFT_PREVIEW,
                    Arg.n("id", view.id()),
                    Arg.s("target", nameOf(view.uuid())),
                    Arg.measure(view.measure()),
                    Arg.key("kind", MessageKey.liftKindKey(spec.kind()))));
            return;
        }
        storage.chainNodes(view.id()).thenAccept(nodes -> {
            int liftable = 0;
            for (final SanctionView node : nodes) {
                if (node.liftedAt() == 0L) {
                    liftable++;
                }
            }
            final int extra = Math.max(0, liftable - 1);
            runMain(() -> {
                messages.send(actor, MessageKey.LIFT_PREVIEW,
                        Arg.n("id", view.id()),
                        Arg.s("target", nameOf(view.uuid())),
                        Arg.measure(view.measure()),
                        Arg.key("kind", MessageKey.liftKindKey(spec.kind())));
                messages.send(actor, MessageKey.LIFT_PREVIEW_CASCADE, Arg.n("count", extra));
            });
        });
    }

    // ----------------------------------------------------------------------
    // Execution
    // ----------------------------------------------------------------------

    /**
     * Lifts one sanction and applies its side effects.
     *
     * @param actor    the lifting sender
     * @param rankId   the actor's resolved rank
     * @param view     the loaded sanction
     * @param spec     the resolved lift description
     * @param now      the lift timestamp
     * @param announce whether success reporting and announcement run; the
     *                 cascade path passes false for chain members and reports
     *                 an aggregate instead
     */
    private void single(final CommandSender actor, final String rankId, final SanctionView view,
                        final ResolvedLift spec, final long now, final boolean announce) {
        final long probationUntil = spec.probationFor() != null
                ? now + spec.probationFor().millis() : 0L;
        storage.liftSanction(view.id(), actor.getName(), now, spec.reason(),
                        spec.kind().code(), probationUntil)
                .whenComplete((rows, err) -> runMain(() -> {
                    if (err != null) {
                        messages.send(actor, MessageKey.ERROR_ENFORCE_FAILED,
                                Arg.s("error", rootMessage(err)));
                        log.error("lift", err, "lift failed for sanction #%d", view.id());
                        return;
                    }
                    if (rows == 0) {
                        if (announce) {
                            messages.send(actor, MessageKey.LIFT_ALREADY, Arg.n("id", view.id()));
                        }
                        return;
                    }
                    reconcileUuid(UUID.fromString(view.uuid()), now, true);
                    network.publishBehaviourSync(UUID.fromString(view.uuid()));
                    if (spec.note() != null) {
                        storage.addCaseNote(view.id(), view.uuid(), actor.getName(),
                                        spec.note(), now)
                                .exceptionally(noteErr -> {
                                    log.warn("lift", "case note failed for #%d: %s",
                                            view.id(), noteErr.getMessage());
                                    return -1L;
                                });
                    }
                    if (announce) {
                        messages.send(actor, MessageKey.LIFT_SUCCESS,
                                Arg.n("id", view.id()),
                                Arg.s("target", nameOf(view.uuid())),
                                Arg.measure(view.measure()),
                                Arg.key("kind", MessageKey.liftKindKey(spec.kind())),
                                Arg.s("actor", actor.getName()));
                        if (!spec.silent()) {
                            announce(view, actor.getName(), rankId, spec.kind());
                        }
                        if (spec.notifyChannels() != null) {
                            notifications.dispatch(List.of(spec.notifyChannels()),
                                    MessageKey.NOTIFY_LIFT,
                                    Arg.s("staff", actor.getName()),
                                    Arg.rank(rankId, "by"),
                                    Arg.s("target", nameOf(view.uuid())),
                                    Arg.measure(view.measure()),
                                    Arg.n("id", view.id()),
                                    Arg.key("kind", MessageKey.liftKindKey(spec.kind())));
                        }
                    }
                    log.info("lift", "#%d %s [%s] lifted (%s) by %s",
                            view.id(), nameOf(view.uuid()), view.measure(),
                            spec.kind(), actor.getName());
                }));
    }

    /**
     * Lifts every unlifted member of the target's joinder chain under the
     * root's kind and reason. Runs on the asynchronous continuation; joining
     * per-member storage stages is safe on the virtual-thread scheduler.
     */
    private void cascade(final CommandSender actor, final String rankId, final SanctionView root,
                         final ResolvedLift spec, final long now) {
        storage.chainNodes(root.id()).thenAccept(nodes -> {
            final long probationUntil = spec.probationFor() != null
                    ? now + spec.probationFor().millis() : 0L;
            int lifted = 0;
            final Set<String> subjects = new HashSet<>(4);
            for (final SanctionView node : nodes) {
                if (node.liftedAt() != 0L) {
                    continue;
                }
                final int rows = storage.liftSanction(node.id(), actor.getName(), now,
                        spec.reason(), spec.kind().code(), probationUntil).join();
                if (rows > 0) {
                    lifted++;
                    subjects.add(node.uuid());
                }
            }
            if (spec.note() != null) {
                storage.addCaseNote(root.id(), root.uuid(), actor.getName(), spec.note(), now)
                        .exceptionally(noteErr -> -1L);
            }
            final int total = lifted;
            runMain(() -> {
                for (final String uuid : subjects) {
                    reconcileUuid(UUID.fromString(uuid), now, true);
                    network.publishBehaviourSync(UUID.fromString(uuid));
                }
                messages.send(actor, MessageKey.LIFT_SUCCESS,
                        Arg.n("id", root.id()),
                        Arg.s("target", nameOf(root.uuid())),
                        Arg.measure(root.measure()),
                        Arg.key("kind", MessageKey.liftKindKey(spec.kind())),
                        Arg.s("actor", actor.getName()));
                messages.send(actor, MessageKey.LIFT_CASCADE_DONE, Arg.n("count", total));
                if (!spec.silent()) {
                    announce(root, actor.getName(), rankId, spec.kind());
                }
                log.info("lift", "chain of #%d: %d sanction(s) lifted (%s) by %s",
                        root.id(), total, spec.kind(), actor.getName());
            });
        }).exceptionally(err -> {
            runMain(() -> messages.send(actor, MessageKey.ERROR_ENFORCE_FAILED,
                    Arg.s("error", rootMessage(err))));
            return null;
        });
    }

    // ----------------------------------------------------------------------
    // Effects
    // ----------------------------------------------------------------------

    /**
     * Recomputes an online subject's behavioural state from their remaining
     * active sanctions and reconciles concealment. A no-op for an offline
     * subject.
     *
     * @param uuid   the subject account
     * @param now    the reference time
     * @param inform whether the behaviour-lifted notice is sent
     */
    private void reconcileUuid(final UUID uuid, final long now, final boolean inform) {
        final Player online = plugin.getServer().getPlayer(uuid);
        if (online == null) {
            return;
        }
        storage.activeBehaviours(uuid.toString(), now).whenComplete((rowsList, err) -> runMain(() -> {
            if (err != null) {
                log.error("lift", err, "behaviour reconcile failed for %s", online.getName());
                return;
            }
            behaviourManager.remove(uuid);
            for (final BehaviourRow row : rowsList) {
                behaviourManager.apply(uuid, row.behaviourMask(), row.expiresAt(), row.reason());
            }
            behaviourEffects.reconcileConcealment(online, System.currentTimeMillis());
            if (inform) {
                messages.send(online, MessageKey.BEHAVIOUR_LIFTED);
            }
        }));
    }

    /**
     * Applies the effects of a reinstated sanction to an online subject: the
     * behavioural state is rebuilt, and a restored connection-blocking
     * sanction removes a non-shadowed subject from the server.
     *
     * @param view the reinstated sanction as it stood before the unlift
     * @param now  the reference time
     */
    private void applyReinstated(final SanctionView view, final long now) {
        final UUID uuid = UUID.fromString(view.uuid());
        final Player online = plugin.getServer().getPlayer(uuid);
        if (online == null) {
            return;
        }
        if (blocksConnection(view.measure()) && !Behaviour.SHADOWED.in(view.behaviourMask())) {
            final MessageKey measureKey = MessageKey.measureKey(view.measure());
            final String fallback = measureKey != null
                    ? messages.plain(online, measureKey) : view.measure();
            final String tag = messages.localeTagFor(online);
            online.kick(messages.render(tag, MessageKey.MECHANISM_KICK,
                    Arg.s("reason", view.reason() != null ? view.reason() : fallback)));
            return;
        }
        storage.activeBehaviours(uuid.toString(), now).whenComplete((rowsList, err) -> runMain(() -> {
            if (err != null) {
                log.error("lift", err, "behaviour reconcile failed for %s", online.getName());
                return;
            }
            behaviourManager.remove(uuid);
            for (final BehaviourRow row : rowsList) {
                behaviourManager.apply(uuid, row.behaviourMask(), row.expiresAt(), row.reason());
            }
            behaviourEffects.reconcileConcealment(online, System.currentTimeMillis());
        }));
    }

    private void announce(final SanctionView view, final String actor,
                          final String rankId, final LiftKind kind) {
        if (!broadcast.enabled() || !broadcast.announceLift()) {
            return;
        }
        final String name = nameOf(view.uuid());
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            messages.send(player, MessageKey.LIFT_BROADCAST,
                    Arg.s("target", name),
                    Arg.s("staff", actor),
                    Arg.rank(rankId, "by"),
                    Arg.measure(view.measure()),
                    Arg.key("kind", MessageKey.liftKindKey(kind)));
        }
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    private static boolean blocksConnection(final String measureName) {
        try {
            return Measure.parse(measureName).blocksConnection();
        } catch (final IllegalArgumentException unknown) {
            return false;
        }
    }

    private String nameOf(final String uuid) {
        final OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(uuid));
        return op.getName() != null ? op.getName() : uuid;
    }

    private void runMain(final Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    private static String rootMessage(final Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        final String m = c.getMessage();
        return m != null ? m : c.getClass().getSimpleName();
    }
}