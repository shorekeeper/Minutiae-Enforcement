package org.synergyst.minutiae.execute;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.synergyst.minutiae.annotation.AnnotationRegistry;
import org.synergyst.minutiae.annotation.RawAnnotation;
import org.synergyst.minutiae.behaviour.Behaviour;
import org.synergyst.minutiae.behaviour.BehaviourEffects;
import org.synergyst.minutiae.behaviour.BehaviourManager;
import org.synergyst.minutiae.command.RecentIds;
import org.synergyst.minutiae.fingerprint.CaptureArrays;
import org.synergyst.minutiae.fingerprint.FingerprintService;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.measure.Measure;
import org.synergyst.minutiae.message.Arg;
import org.synergyst.minutiae.message.MessageKey;
import org.synergyst.minutiae.message.MessageService;
import org.synergyst.minutiae.message.ReasonData;
import org.synergyst.minutiae.net.NetworkBus;
import org.synergyst.minutiae.net.ServerIdentity;
import org.synergyst.minutiae.notify.NotificationService;
import org.synergyst.minutiae.preference.PreferenceService;
import org.synergyst.minutiae.rank.RankConfig;
import org.synergyst.minutiae.resolve.ResolvedSanction;
import org.synergyst.minutiae.storage.*;
import org.synergyst.minutiae.time.DurationSpec;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Applies resolved sanctions: routes amend and joinder requests, consults
 * precedent (rule-scoped or joinder-chain-scoped), evaluates the warn-first
 * gate, computes probation-aware escalation, persists the record, captures
 * fingerprint signals, activates lapsed stays, applies the enforcement
 * mechanism, reports, announces, and dispatches notifications.
 *
 * <p>All persistence occurs on the asynchronous scheduler; player-facing effects
 * and reporting are marshalled to the server main thread.
 *
 * <p>Localisation posture: every measure shown to a player or staff member is
 * rendered through the rich {@code measure} argument, so broadcasts and
 * notifications display each recipient's localised measure name rather than
 * the enum constant. Persisted fields (the recorded reason fallback, the
 * annotation display string, audit detail) retain canonical raw forms, since a
 * stored string cannot be re-localised per viewer after the fact.
 */
public final class SanctionExecutor {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final KernelLogger log;
    private final Storage storage;
    private final AnnotationRegistry annotations;
    private final FingerprintService fingerprint;
    private final MessageService messages;
    private final BehaviourManager behaviourManager;
    private final BehaviourEffects behaviourEffects;
    private final NotificationService notifications;
    private final PreferenceService preferences;
    private final BroadcastConfig broadcast;
    private final EscalationConfig escalationConfig;
    private final org.synergyst.minutiae.chat.ChatHistoryService chatHistory;
    private final NetworkBus network;
    private final ServerIdentity identity;
    private final RankConfig ranks;
    private final RecentIds recentIds;
    private final JavaPlugin plugin;

    public SanctionExecutor(final KernelLogger log,
                            final Storage storage,
                            final AnnotationRegistry annotations,
                            final FingerprintService fingerprint,
                            final MessageService messages,
                            final BehaviourManager behaviourManager,
                            final BehaviourEffects behaviourEffects,
                            final NotificationService notifications,
                            final PreferenceService preferences,
                            final BroadcastConfig broadcast,
                            final EscalationConfig escalationConfig,
                            final org.synergyst.minutiae.chat.ChatHistoryService chatHistory,
                            final NetworkBus network,
                            final ServerIdentity identity,
                            final RankConfig ranks,
                            final RecentIds recentIds,
                            final JavaPlugin plugin) {
        this.log = log;
        this.storage = storage;
        this.annotations = annotations;
        this.fingerprint = fingerprint;
        this.messages = messages;
        this.behaviourManager = behaviourManager;
        this.behaviourEffects = behaviourEffects;
        this.notifications = notifications;
        this.preferences = preferences;
        this.broadcast = broadcast;
        this.escalationConfig = escalationConfig;
        this.chatHistory = chatHistory;
        this.network = network;
        this.identity = identity;
        this.ranks = ranks;
        this.recentIds = recentIds;
        this.plugin = plugin;
    }

    /**
     * Executes a resolved sanction on behalf of an issuer, routing amend and
     * joinder requests before falling through to fresh issuance.
     *
     * @param r      the resolved sanction
     * @param issuer the command sender
     */
    public void execute(final ResolvedSanction r, final CommandSender issuer) {
        if (r.amendId() != 0L) {
            amend(r, issuer);
            return;
        }
        if (r.weaveParent() != 0L) {
            storage.findSanction(r.weaveParent()).whenComplete((parent, err) -> {
                if (err != null) {
                    runMain(() -> messages.send(issuer, MessageKey.ERROR_ENFORCE_FAILED,
                            Arg.s("error", rootMessage(err))));
                    return;
                }
                if (parent == null) {
                    runMain(() -> messages.send(issuer, MessageKey.ERROR_WEAVE_PARENT_NOT_FOUND,
                            Arg.n("id", r.weaveParent())));
                    return;
                }
                runMain(() -> dispatch(r, issuer));
            });
            return;
        }
        dispatch(r, issuer);
    }

    /**
     * Base-duration and ladder-index inputs to a decision trace.
     *
     * @param ladderIndex escalation rung selected, or {@code -1} when none
     * @param baseMs      base duration in milliseconds before the tariff floor,
     *                    {@code -1} for permanent, {@code 0} for none
     */
    private record TraceBase(int ladderIndex, long baseMs) {
    }

    /**
     * Recomputes the escalation base and selected ladder index for the decision
     * trace, mirroring the duration derivation without applying the tariff floor.
     *
     * @param r         the resolved sanction
     * @param measure   the effective measure
     * @param precedent the consulted precedent
     * @return the trace base inputs
     */
    private TraceBase traceBase(final ResolvedSanction r, final Measure measure,
                                final Precedent precedent) {
        if (measure.temporal() != Measure.Temporal.TEMPORAL) {
            return new TraceBase(-1, 0L);
        }
        final boolean escalating = r.escalate() && !r.durationOverridden()
                && r.escalation().length > 0;
        if (escalating) {
            int index = Math.min(precedent.priorSanctions(), r.escalation().length - 1);
            if (precedent.inProbation() && escalationConfig.probationStep()) {
                index = Math.min(precedent.priorSanctions() + 1, r.escalation().length - 1);
            }
            DurationSpec b = r.escalation()[index];
            if (precedent.inProbation() && !escalationConfig.probationStep() && !b.permanent()) {
                b = new DurationSpec(
                        Math.round(b.millis() * escalationConfig.probationMultiplier()), false);
            }
            return new TraceBase(index, b.permanent() ? -1L : b.millis());
        }
        if (r.duration() != null) {
            return new TraceBase(-1, r.duration().permanent() ? -1L : r.duration().millis());
        }
        return new TraceBase(-1, 0L);
    }

    // ----------------------------------------------------------------------
    // Amend
    // ----------------------------------------------------------------------

    private void amend(final ResolvedSanction r, final CommandSender issuer) {
        final long now = System.currentTimeMillis();
        storage.findSanction(r.amendId()).thenAccept(view -> {
            if (view == null) {
                runMain(() -> messages.send(issuer, MessageKey.LIFT_NOT_FOUND, Arg.n("id", r.amendId())));
                return;
            }
            final Long newExpires;
            if (r.durationOverridden() && r.duration() != null) {
                newExpires = r.duration().permanent() ? 0L : view.issuedAt() + r.duration().millis();
            } else {
                newExpires = null;
            }
            storage.amendSanction(r.amendId(), r.rule(), r.reason(), newExpires, r.counts(),
                            issuer.getName(), now)
                    .whenComplete((result, err) -> runMain(() -> {
                        if (err != null) {
                            messages.send(issuer, MessageKey.ERROR_ENFORCE_FAILED,
                                    Arg.s("error", rootMessage(err)));
                            log.error("amend", err, "amend failed for sanction #%d", r.amendId());
                            return;
                        }
                        if (!result.found()) {
                            messages.send(issuer, MessageKey.LIFT_NOT_FOUND, Arg.n("id", r.amendId()));
                            return;
                        }
                        reconcileAmend(result, now);
                        messages.send(issuer, MessageKey.SANCTION_AMENDED,
                                Arg.n("id", r.amendId()), Arg.s("diff", result.diff()));
                        log.info("amend", "#%d amended by %s: %s",
                                r.amendId(), issuer.getName(), result.diff());
                    }));
        }).exceptionally(err -> {
            runMain(() -> messages.send(issuer, MessageKey.ERROR_ENFORCE_FAILED,
                    Arg.s("error", rootMessage(err))));
            return null;
        });
    }

    private void reconcileAmend(final AmendResult result, final long now) {
        if (result.behaviourMask() == 0L) {
            return;
        }
        final UUID uuid = UUID.fromString(result.uuid());
        network.publishBehaviourSync(uuid);
        final Player online = plugin.getServer().getPlayer(uuid);
        if (online == null) {
            return;
        }
        storage.activeBehaviours(uuid.toString(), now).whenComplete((rows, err) -> runMain(() -> {
            if (err != null) {
                log.error("amend", err, "behaviour reconcile failed for %s", online.getName());
                return;
            }
            behaviourManager.remove(uuid);
            for (final BehaviourRow row : rows) {
                behaviourManager.apply(uuid, row.behaviourMask(), row.expiresAt(), row.reason());
            }
            behaviourEffects.reconcileConcealment(online, System.currentTimeMillis());
        }));
    }

    // ----------------------------------------------------------------------
    // Fresh issuance
    // ----------------------------------------------------------------------

    private void dispatch(final ResolvedSanction r, final CommandSender issuer) {
        final OfflinePlayer target = plugin.getServer().getOfflinePlayer(r.target());
        final UUID uuid = target.getUniqueId();
        final String staff = r.attributedStaff() != null ? r.attributedStaff() : issuer.getName();
        // Rank resolution touches live permissions only for player issuers,
        // which reach this method on the main thread by construction; console
        // and synthetic senders resolve without any Bukkit call.
        final String rankId = ranks.resolve(issuer);
        final long now = System.currentTimeMillis();
        final long issuedAt = now - (r.backdateFor() != null ? r.backdateFor().millis() : 0L);

        final CompletableFuture<Precedent> precedentFuture = r.weaveParent() != 0L
                ? storage.chainPrecedent(r.weaveParent(), now)
                : storage.precedent(uuid.toString(), r.rule(), now);

        precedentFuture.thenAccept(precedent -> {
            final boolean warned = warnGate(r, precedent);
            final Measure measure = warned ? Measure.WARN : r.measure();

            final DurationSpec fin = finalDuration(r, measure, precedent);
            final boolean escalated = r.escalate() && !r.durationOverridden()
                    && r.escalation().length > 0
                    && measure.temporal() == Measure.Temporal.TEMPORAL;
            final long expiresAt = expiry(fin, issuedAt, measure);
            final long behaviourMask = warned ? 0L : behaviourMaskOf(r, measure);
            final long probationUntil = r.probationFor() != null
                    ? issuedAt + r.probationFor().millis() : 0L;

            if (r.dryRun()) {
                final ExecutionOutcome preview = new ExecutionOutcome(-1L, measure, fin, expiresAt,
                        behaviourMask, precedent.priorSanctions(), escalated, 0, 0,
                        r.stayFor() != null, !warned && r.suspendFor() != null,
                        warned, precedent.priorWarnings() + 1, r.warnFirst());
                runMain(() -> renderPreview(issuer, r, preview, issuedAt, staff));
                return;
            }

            final boolean stayed = r.stayFor() != null;
            final boolean suspended = !warned && r.suspendFor() != null;
            final long stayUntil = stayed ? now + r.stayFor().millis() : 0L;
            final long suspendUntil = suspended ? issuedAt + r.suspendFor().millis() : 0L;
            final long decayAt = r.decayFor() != null ? issuedAt + r.decayFor().millis() : 0L;
            final long expungeAt = r.expungeFor() != null ? issuedAt + r.expungeFor().millis() : 0L;
            final long reviewAt = r.reviewFor() != null ? issuedAt + r.reviewFor().millis() : 0L;
            final int active = activeFlag(measure, stayed, suspended);

            final SanctionRow row = new SanctionRow(
                    uuid.toString(), r.layoutKey(), r.rule(), r.reason(),
                    issuedAt, expiresAt, staff, renderAnnotations(r), active,
                    measure.name(), stayed ? 1 : 0, stayUntil, r.link(),
                    behaviourMask, r.provisional() ? 1 : 0, decayAt, r.appealable() ? 1 : 0,
                    r.weaveParent(), probationUntil, r.target(), identity.id(),
                    suspended ? 1 : 0, suspendUntil, expungeAt, reviewAt);

            final ExecutionOutcome base = new ExecutionOutcome(-1L, measure, fin, expiresAt,
                    behaviourMask, precedent.priorSanctions(), escalated, 0, 0, stayed,
                    suspended, warned, precedent.priorWarnings() + 1, r.warnFirst());

            final boolean wantTranscript = chatHistory.shouldCapture(
                    r.explicit().containsKey("transcript"),
                    measure.blocksConnection() || behaviourMask != 0L);
            final org.synergyst.minutiae.chat.ChatSnapshot transcript =
                    wantTranscript ? chatHistory.snapshot(uuid)
                            : org.synergyst.minutiae.chat.ChatSnapshot.EMPTY;

            final TraceBase traceBase = traceBase(r, measure, precedent);
            final long finalMs = fin.permanent() ? -1L : fin.millis();

            storage.recordUsername(uuid.toString(), r.target(), now)
                    .exceptionally(err -> null);

            storage.persistSanction(row, r.counts())
                    .thenCompose(id -> {
                        final CaptureArrays capture = measure.blocksConnection()
                                ? fingerprint.capture(uuid)
                                : new CaptureArrays(new int[0], new String[0], new double[0]);
                        final CompletableFuture<Void> signals = capture.size() == 0
                                ? CompletableFuture.completedFuture(null)
                                : storage.persistSignals(id, capture.types(),
                                capture.values(), capture.weights());
                        return signals
                                .thenCompose(ignored -> noteStage(id, uuid, staff, r, now))
                                .thenCompose(ignored -> chatHistory.persist(id, transcript))
                                .thenCompose(ignored -> storage.persistTrace(new SanctionTraceRow(
                                        id, precedent.priorSanctions(), precedent.priorWarnings(),
                                        precedent.inProbation(), escalated, traceBase.ladderIndex(),
                                        traceBase.baseMs(), finalMs, warned)))
                                .thenCompose(ignored -> storage.activateStays(uuid.toString(), now))
                                .thenCompose(act -> storage.activateSuspended(
                                                uuid.toString(), r.rule(), now)
                                        .thenApply(sact -> withId(base, id, act, sact)));
                    })
                    .whenComplete((outcome, err) -> runMain(() -> {
                        if (err != null) {
                            messages.send(issuer, MessageKey.ERROR_ENFORCE_FAILED,
                                    Arg.s("error", rootMessage(err)));
                            log.error("enforce", err, "sanction execution failed for %s", r.target());
                            return;
                        }
                        recentIds.recordSanction(outcome.banId(), uuid); // <- inserted
                        applyMechanism(target, r, outcome);
                        if (!outcome.stayed() && !outcome.warned()) {
                            if (outcome.effectiveMeasure().blocksConnection()
                                    && !Behaviour.SHADOWED.in(outcome.behaviourMask())) {
                                network.publishKick(uuid, r.reason() != null
                                        ? r.reason() : outcome.effectiveMeasure().name());
                            }
                            if (outcome.behaviourMask() != 0L) {
                                network.publishBehaviourSync(uuid);
                            }
                        }
                        propagate(r, outcome, uuid);
                        report(issuer, r, outcome, issuedAt, staff);
                        announce(r, outcome, staff, rankId, issuedAt);
                        dispatchNotifications(r, outcome, staff, rankId);
                        log.info("enforce", "%s -> %s [%s] rule=%s by=%s id=%d%s%s",
                                r.target(), outcome.effectiveMeasure(),
                                outcome.finalDuration().format(),
                                r.rule() != null ? r.rule() : "-", staff, outcome.banId(),
                                outcome.warned() ? " (warning)" : "",
                                r.weaveParent() != 0L ? " woven=#" + r.weaveParent() : "");
                    }));
        }).exceptionally(err -> {
            runMain(() -> messages.send(issuer, MessageKey.ERROR_ENFORCE_FAILED,
                    Arg.s("error", rootMessage(err))));
            return null;
        });
    }

    /**
     * Publishes the network directives a persisted sanction requires: a
     * remote kick for a non-shadowed connection-blocking measure, and a
     * behavioural reconciliation for any behavioural mask. A stayed or
     * warn-downgraded sanction applied no effect and propagates nothing; the
     * bus of a standalone deployment absorbs both calls as no-ops.
     */
    private void propagate(final ResolvedSanction r, final ExecutionOutcome outcome,
                           final UUID uuid) {
        if (outcome.stayed() || outcome.warned()) {
            return;
        }
        if (outcome.effectiveMeasure().blocksConnection()
                && !Behaviour.SHADOWED.in(outcome.behaviourMask())) {
            network.publishKick(uuid, r.reason() != null
                    ? r.reason() : outcome.effectiveMeasure().name());
        }
        if (outcome.behaviourMask() != 0L) {
            network.publishBehaviourSync(uuid);
        }
    }

    // ----------------------------------------------------------------------
    // Gate and duration
    // ----------------------------------------------------------------------

    private boolean warnGate(final ResolvedSanction r, final Precedent precedent) {
        return r.warnFirst() > 0
                && !r.now()
                && r.measure() != Measure.WARN
                && precedent.priorWarnings() < r.warnFirst();
    }

    private DurationSpec finalDuration(final ResolvedSanction r, final Measure measure,
                                       final Precedent precedent) {
        return switch (measure.temporal()) {
            case PERMANENT -> DurationSpec.PERMANENT;
            case INSTANTANEOUS -> DurationSpec.ZERO;
            case TEMPORAL -> {
                DurationSpec base;
                final boolean escalating = r.escalate() && !r.durationOverridden()
                        && r.escalation().length > 0;
                if (escalating) {
                    int index = Math.min(precedent.priorSanctions(), r.escalation().length - 1);
                    // A recidivism within an active probation window aggravates the
                    // outcome: it advances the ladder by an extra rung, or, in
                    // multiplier mode, scales the selected rung.
                    if (precedent.inProbation() && escalationConfig.probationStep()) {
                        index = Math.min(precedent.priorSanctions() + 1, r.escalation().length - 1);
                    }
                    base = r.escalation()[index];
                    if (precedent.inProbation() && !escalationConfig.probationStep()
                            && !base.permanent()) {
                        final long scaled = Math.round(base.millis() * escalationConfig.probationMultiplier());
                        base = new DurationSpec(scaled, false);
                    }
                } else {
                    base = r.duration();
                }
                if (r.tariffFor() != null && !base.permanent()
                        && base.millis() < r.tariffFor().millis()) {
                    base = r.tariffFor();
                }
                yield base;
            }
        };
    }

    private long expiry(final DurationSpec fin, final long issuedAt, final Measure m) {
        if (fin.permanent() || m.temporal() == Measure.Temporal.INSTANTANEOUS) {
            return 0L;
        }
        return issuedAt + fin.millis();
    }

    private int activeFlag(final Measure m, final boolean stayed) {
        if (stayed || m.temporal() == Measure.Temporal.INSTANTANEOUS) {
            return 0;
        }
        return 1;
    }

    private long behaviourMaskOf(final ResolvedSanction r, final Measure measure) {
        long mask = 0L;
        switch (measure) {
            case MUTE -> mask |= Behaviour.MUTED.bit();
            case QUARANTINE -> mask |= Behaviour.QUARANTINED.bit();
            default -> {
            }
        }
        if (r.explicit().containsKey("shadow")) {
            mask |= Behaviour.SHADOWED.bit();
        }
        if (r.explicit().containsKey("ghost")) {
            mask |= Behaviour.GHOSTED.bit();
        }
        if (r.explicit().containsKey("rubberband")) {
            mask |= Behaviour.RUBBERBAND.bit();
        }
        return mask;
    }

    // ----------------------------------------------------------------------
    // Mechanism
    // ----------------------------------------------------------------------

    private void applyMechanism(final OfflinePlayer target, final ResolvedSanction r,
                                final ExecutionOutcome outcome) {
        if (outcome.stayed() || outcome.suspended()) { return; }
        final Measure measure = outcome.effectiveMeasure();
        final long mask = outcome.behaviourMask();
        final boolean shadowed = Behaviour.SHADOWED.in(mask);
        // The recorded reason keeps the canonical raw fallback, since it is
        // persisted state; the player-facing fallback is localised per
        // recipient at the display sites below.
        final String recordedReason = r.reason() != null ? r.reason() : measure.name();

        if (mask != 0L) {
            behaviourManager.apply(target.getUniqueId(), mask, outcome.expiresAt(), recordedReason);
        }

        final Player online = target.isOnline() ? target.getPlayer() : null;
        switch (measure) {
            case WARN, CENSURE -> {
                if (online != null) {
                    messages.send(online, MessageKey.MECHANISM_WARN,
                            Arg.s("reason", displayReason(online, r, measure)));
                }
            }
            case KICK -> {
                if (online != null) {
                    kick(online, displayReason(online, r, measure));
                }
            }
            case SUSPENSION, CUSTODY -> {
                if (online != null && !shadowed) {
                    kick(online, displayReason(online, r, measure));
                }
            }
            case MUTE, QUARANTINE -> {
            }
        }

        if (online != null && mask != 0L && !(measure.blocksConnection() && !shadowed)) {
            behaviourEffects.apply(online, System.currentTimeMillis());
        }
    }

    /**
     * Resolves the player-facing reason of a sanction: the explicit reason
     * when present, otherwise the measure's display name in the recipient's
     * locale.
     *
     * @param recipient the player who will see the reason
     * @param r         the resolved sanction
     * @param measure   the effective measure
     * @return the display reason
     */
    private String displayReason(final Player recipient, final ResolvedSanction r,
                                 final Measure measure) {
        if (r.reason() != null) {
            return r.reason();
        }
        return messages.plain(recipient, MessageKey.measureKey(measure));
    }

    private void kick(final Player online, final String reason) {
        final String tag = messages.localeTagFor(online);
        online.kick(messages.render(tag, MessageKey.MECHANISM_KICK, Arg.s("reason", reason)));
    }

    // ----------------------------------------------------------------------
    // Reporting, broadcast, notification
    // ----------------------------------------------------------------------

    /**
     * Builds the reason hover-card data for one recipient. The duration field
     * is localised to the recipient's locale at construction, since the card
     * data carries plain strings.
     *
     * @param to       the recipient
     * @param r        the resolved sanction
     * @param o        the execution outcome
     * @param staff    the attributed staff name
     * @param issuedAt the issue timestamp
     * @return the card data
     */
    private ReasonData reasonData(final CommandSender to, final ResolvedSanction r,
                                  final ExecutionOutcome o, final String staff, final long issuedAt) {
        return new ReasonData(
                r.reason(),
                o.effectiveMeasure().name(),
                r.rule() != null ? r.rule() : "-",
                messages.durationText(messages.localeTagFor(to), o.finalDuration()),
                staff,
                WHEN.format(Instant.ofEpochMilli(issuedAt)));
    }

    private void renderPreview(final CommandSender to, final ResolvedSanction r,
                               final ExecutionOutcome o, final long issuedAt, final String staff) {
        messages.send(to, MessageKey.SANCTION_DRY_RUN);
        verboseLines(to, r, o, issuedAt, staff);
        messages.send(to, MessageKey.SANCTION_DRY_RUN_FOOTER);
    }

    private void report(final CommandSender to, final ResolvedSanction r,
                        final ExecutionOutcome o, final long issuedAt, final String staff) {
        if (!preferences.isVerbose(to)) {
            messages.send(to, MessageKey.SANCTION_COMPACT,
                    Arg.s("target", r.target()),
                    Arg.measure(o.effectiveMeasure().name()),
                    Arg.duration(o.finalDuration()),
                    Arg.reason(reasonData(to, r, o, staff, issuedAt)));
            return;
        }
        if (o.warned()) {
            messages.send(to, MessageKey.SANCTION_WARNING);
        } else if (o.suspended()) {
            messages.send(to, MessageKey.SANCTION_SUSPENDED);
        } else {
            messages.send(to, o.stayed() ? MessageKey.SANCTION_STAYED : MessageKey.SANCTION_APPLIED);
        }
        verboseLines(to, r, o, issuedAt, staff);
        if (o.staysActivated() > 0) {
            messages.send(to, MessageKey.LINE_STAYS_ACTIVATED, Arg.n("count", o.staysActivated()));
        }
        if (o.suspendedActivated() > 0) {
            messages.send(to, MessageKey.LINE_SUSPENDED_ACTIVATED,
                    Arg.n("count", o.suspendedActivated()));
        }
        messages.send(to, MessageKey.LINE_ID, Arg.n("id", o.banId()));
    }

    private void verboseLines(final CommandSender to, final ResolvedSanction r,
                              final ExecutionOutcome o, final long issuedAt, final String staff) {
        messages.send(to, MessageKey.LINE_TARGET, Arg.s("target", r.target()));
        messages.send(to, MessageKey.LINE_MEASURE, Arg.measure(o.effectiveMeasure().name()));
        messages.send(to, MessageKey.LINE_LAYOUT,
                Arg.s("layout", r.layoutKey() != null ? "::" + r.layoutKey()
                        : messages.plain(to, MessageKey.SANCTION_LAYOUT_MANUAL)));
        messages.send(to, MessageKey.LINE_RULE, Arg.s("rule", ruleWithCounts(r)));
        messages.send(to, MessageKey.LINE_REASON, Arg.reason(reasonData(to, r, o, staff, issuedAt)));
        if (o.warned()) {
            messages.send(to, MessageKey.LINE_WARNING,
                    Arg.n("count", o.warningNumber()), Arg.n("required", o.warnRequired()));
        } else if (o.escalated()) {
            messages.send(to, MessageKey.LINE_DURATION_ESCALATED,
                    Arg.duration(o.finalDuration()), Arg.n("prior", o.priorSanctions()));
        } else {
            messages.send(to, MessageKey.LINE_DURATION, Arg.duration(o.finalDuration()));
        }
        if (r.stayFor() != null) {
            messages.send(to, MessageKey.LINE_STAY, Arg.duration(r.stayFor()));
        }
        messages.send(to, MessageKey.LINE_FLAGS, Arg.s("flags", flagsLine(to, r)));
    }

    private void announce(final ResolvedSanction r, final ExecutionOutcome o,
                          final String staff, final String rankId, final long issuedAt) {
        if (o.stayed() || o.warned() || o.suspended()) { return; }
        final boolean silent = r.explicit().containsKey("silent") || r.explicit().containsKey("shadow");
        if (silent || !broadcast.broadcasts(o.effectiveMeasure())) {
            return;
        }
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            messages.send(player, MessageKey.BROADCAST_SANCTION,
                    Arg.s("target", r.target()),
                    Arg.s("staff", staff),
                    Arg.rank(rankId, "by"),
                    Arg.measure(o.effectiveMeasure().name()),
                    Arg.duration(o.finalDuration()),
                    Arg.reason(reasonData(player, r, o, staff, issuedAt)));
        }
    }

    private void dispatchNotifications(final ResolvedSanction r, final ExecutionOutcome outcome,
                                       final String staff, final String rankId) {
        if (r.notifyChannels() == null) {
            return;
        }
        notifications.dispatch(List.of(r.notifyChannels()), MessageKey.NOTIFY_SUMMARY,
                Arg.s("staff", staff),
                Arg.rank(rankId, "by"),
                Arg.measure(outcome.effectiveMeasure().name()),
                Arg.s("target", r.target()),
                Arg.s("rule", r.rule() != null ? r.rule() : "-"),
                Arg.duration(outcome.finalDuration()),
                Arg.s("reason", r.reason() != null ? r.reason() : "-"));
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    private String renderAnnotations(final ResolvedSanction r) {
        final StringBuilder sb = new StringBuilder(64);
        for (final RawAnnotation a : r.explicit().values()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(a.toDisplay());
        }
        return sb.toString();
    }

    private String ruleWithCounts(final ResolvedSanction r) {
        final StringBuilder sb = new StringBuilder();
        sb.append(r.rule() != null ? r.rule() : "-");
        for (final String c : r.counts()) {
            sb.append(" +").append(c);
        }
        return sb.toString();
    }

    /**
     * Renders the flags line: explicit annotations in their canonical token
     * form followed by implied annotations, the latter marked through the
     * localised implied-flag fragment in the recipient's locale.
     *
     * @param to the report recipient
     * @param r  the resolved sanction
     * @return the flags line, or a dash when no annotation is present
     */
    private String flagsLine(final CommandSender to, final ResolvedSanction r) {
        final StringBuilder sb = new StringBuilder(64);
        for (final RawAnnotation a : r.explicit().values()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(a.toDisplay());
        }
        final List<String> all = annotations.namesOf(r.mask());
        for (final String name : all) {
            if (!r.explicit().containsKey(name)) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(messages.plain(to, MessageKey.SANCTION_FLAG_IMPLIED,
                        Arg.s("name", name)));
            }
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private CompletableFuture<Void> noteStage(final long id, final UUID uuid,
                                              final String staff, final ResolvedSanction r,
                                              final long now) {
        if (r.note() == null || r.note().isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        return storage.addCaseNote(id, uuid.toString(), staff, r.note(), now)
                .thenApply(ignored -> null);
    }

    private int activeFlag(final Measure m, final boolean stayed, final boolean suspended) {
        if (stayed || suspended || m.temporal() == Measure.Temporal.INSTANTANEOUS) {
            return 0;
        }
        return 1;
    }

    private static ExecutionOutcome withId(final ExecutionOutcome base, final long id,
                                           final int stays, final int suspendedActivated) {
        return new ExecutionOutcome(id, base.effectiveMeasure(), base.finalDuration(),
                base.expiresAt(), base.behaviourMask(), base.priorSanctions(), base.escalated(),
                stays, suspendedActivated, base.stayed(), base.suspended(),
                base.warned(), base.warningNumber(), base.warnRequired());
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