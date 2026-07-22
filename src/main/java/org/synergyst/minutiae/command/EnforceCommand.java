package org.synergyst.minutiae.command;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.synergyst.minutiae.annotation.AnnotationRegistry;
import org.synergyst.minutiae.annotation.AnnotationSpec;
import org.synergyst.minutiae.command.dsl.Args;
import org.synergyst.minutiae.command.dsl.CompleteCtx;
import org.synergyst.minutiae.command.dsl.Ctx;
import org.synergyst.minutiae.command.dsl.Node;
import org.synergyst.minutiae.command.parse.CommandParseException;
import org.synergyst.minutiae.command.parse.CommandParser;
import org.synergyst.minutiae.command.parse.CommandTokenizer;
import org.synergyst.minutiae.command.parse.ParsedCommand;
import org.synergyst.minutiae.execute.*;
import org.synergyst.minutiae.layout.LayoutRegistry;
import org.synergyst.minutiae.measure.Measure;
import org.synergyst.minutiae.message.Arg;
import org.synergyst.minutiae.message.MessageKey;
import org.synergyst.minutiae.message.MessageService;
import org.synergyst.minutiae.message.ReasonData;
import org.synergyst.minutiae.resolve.ResolveException;
import org.synergyst.minutiae.resolve.ResolvedSanction;
import org.synergyst.minutiae.resolve.SanctionResolver;
import org.synergyst.minutiae.storage.AppealView;
import org.synergyst.minutiae.storage.SanctionView;
import org.synergyst.minutiae.storage.Storage;
import org.synergyst.minutiae.time.DurationSpec;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The {@code /enforce} command.
 *
 * <p>The command exposes several forms distinguished by leading literal:
 * {@code help} prints usage; {@code lift <id> [reason]} lifts a sanction;
 * {@code info <id>} displays a sanction; and any other input is parsed as a
 * sanction specification by the custom grammar, resolved against the sender's
 * permissions, and dispatched to the executor. Parse and resolution failures are
 * reported through the message service without side effects. Tab completion on
 * the specification form is context-sensitive on the trailing token.
 */
public final class EnforceCommand {

    private static final String PERMISSION = "minutiae.command.enforce";
    private static final String PERMISSION_LIFT = "minutiae.command.lift";
    private static final String PERMISSION_INFO = "minutiae.command.info";
    private static final String PERMISSION_APPEAL_REVIEW = "minutiae.appeal.review";

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final SanctionResolver resolver;
    private final SanctionExecutor executor;
    private final SanctionLift lift;
    private final Storage storage;
    private final LayoutRegistry layouts;
    private final AnnotationRegistry annotations;
    private final MessageService messages;
    private final SpecCompleter specCompleter;
    private final RecentIds recents;
    private final LiftResolver liftResolver;

    public EnforceCommand(final SanctionResolver resolver,
                          final SanctionExecutor executor,
                          final SanctionLift lift,
                          final Storage storage,
                          final LayoutRegistry layouts,
                          final AnnotationRegistry annotations,
                          final MessageService messages,
                          final SpecCompleter specCompleter,
                          final RecentIds recents) {
        this.resolver = resolver;
        this.executor = executor;
        this.lift = lift;
        this.storage = storage;
        this.layouts = layouts;
        this.annotations = annotations;
        this.messages = messages;
        this.specCompleter = specCompleter;
        this.recents = recents;
        this.liftResolver = new LiftResolver(annotations);
    }

    /**
     * Builds the command tree.
     *
     * @return the root node
     */
    /**
     * Builds the command tree.
     *
     * <p>Identifier arguments complete from the recent-identifier ring; the
     * specification argument completes through the context-sensitive
     * {@link SpecCompleter}. Literal branches (lift kinds, appeal verdicts)
     * are suggested by the command framework itself and require no completer.
     *
     * @return the root node
     */
    public Node tree() {
        return Node.literal("enforce")
                .permission(PERMISSION)
                .executes(this::help)
                .then(Node.literal("help").executes(this::help))
                .then(Node.literal("lift")
                        .permission(PERMISSION_LIFT)
                        .then(Node.argument("id", Args.integer(1, Integer.MAX_VALUE))
                                .suggests(ctx -> recents.sanctionIds())
                                .executes(ctx -> doLift(ctx, LiftKind.VACATE, ""))
                                .then(liftKindBranch("vacate", LiftKind.VACATE))
                                .then(liftKindBranch("pardon", LiftKind.PARDON))
                                .then(liftKindBranch("served", LiftKind.TIME_SERVED))
                                .then(Node.argument("spec", Args.greedy())
                                        .executes(ctx -> doLift(ctx, LiftKind.VACATE, ctx.str("spec"))))))
                .then(Node.literal("unlift")
                        .permission("minutiae.lift.reinstate")
                        .then(Node.argument("id", Args.integer(1, Integer.MAX_VALUE))
                                .suggests(ctx -> recents.sanctionIds())
                                .executes(ctx -> lift.unlift(ctx.sender(), ctx.integer("id"), null))
                                .then(Node.argument("reason", Args.greedy())
                                        .executes(ctx -> lift.unlift(ctx.sender(), ctx.integer("id"),
                                                ctx.str("reason"))))))
                .then(Node.literal("liftall")
                        .permission("minutiae.lift.bulk")
                        .then(bulkBranch("rule", Storage.BulkCriterion.RULE))
                        .then(bulkBranch("staff", Storage.BulkCriterion.STAFF)))
                .then(Node.literal("info")
                        .permission(PERMISSION_INFO)
                        .then(Node.argument("id", Args.integer(1, Integer.MAX_VALUE))
                                .suggests(ctx -> recents.sanctionIds())
                                .executes(this::info)))
                .then(Node.argument("spec", Args.greedy())
                        .suggests(specCompleter::complete)
                        .executes(this::execute))
                .then(Node.literal("docket")
                        .permission("minutiae.command.docket")
                        .then(Node.argument("player", Args.word())
                                .suggests(this::completePlayers)
                                .executes(ctx -> docket(ctx, 1))
                                .then(Node.argument("page", Args.integer(1, Integer.MAX_VALUE))
                                        .executes(ctx -> docket(ctx, ctx.integer("page"))))))
                .then(Node.literal("appeals")
                        .permission(PERMISSION_APPEAL_REVIEW)
                        .executes(ctx -> appeals(ctx, 1))
                        .then(Node.argument("page", Args.integer(1, Integer.MAX_VALUE))
                                .executes(ctx -> appeals(ctx, ctx.integer("page")))))
                .then(Node.literal("appeal")
                        .permission(PERMISSION_APPEAL_REVIEW)
                        .then(Node.argument("id", Args.integer(1, Integer.MAX_VALUE))
                                .suggests(ctx -> recents.appealIds())
                                .then(Node.literal("accept")
                                        .executes(ctx -> decide(ctx, "ACCEPTED", null))
                                        .then(Node.argument("reason", Args.greedy())
                                                .executes(ctx -> decide(ctx, "ACCEPTED", ctx.str("reason")))))
                                .then(Node.literal("deny")
                                        .executes(ctx -> decide(ctx, "DENIED", null))
                                        .then(Node.argument("reason", Args.greedy())
                                                .executes(ctx -> decide(ctx, "DENIED", ctx.str("reason")))))));
    }

    // ----------------------------------------------------------------------
    // Sanction issuance
    // ----------------------------------------------------------------------

    private void execute(final Ctx ctx) {
        final ParsedCommand parsed;
        try {
            parsed = CommandParser.parse(CommandTokenizer.tokenize(ctx.str("spec")));
        } catch (final CommandParseException e) {
            if (e.hasKey()) {
                messages.send(ctx.sender(), e.key(), e.args());
            } else {
                messages.send(ctx.sender(), MessageKey.ERROR_PARSE, Arg.s("error", e.getMessage()));
            }
            return;
        }
        final ResolvedSanction resolved;
        try {
            resolved = resolver.resolve(parsed, perm -> ctx.sender().hasPermission(perm));
        } catch (final ResolveException e) {
            if (e.hasKey()) {
                messages.send(ctx.sender(), e.key(), e.args());
            } else {
                messages.send(ctx.sender(), MessageKey.ERROR_RESOLVE, Arg.s("error", e.getMessage()));
            }
            return;
        }
        executor.execute(resolved, ctx.sender());
    }

    // ----------------------------------------------------------------------
    // Lift
    // ----------------------------------------------------------------------

    /**
     * Parses the lift remainder through the lift resolver and dispatches.
     * Resolver failures render through their keys; a raw-string failure falls
     * back to the generic resolve error.
     */
    private void doLift(final Ctx ctx, final LiftKind kind, final String remainder) {
        final ResolvedLift resolved;
        try {
            resolved = liftResolver.resolve(kind, remainder,
                    perm -> ctx.sender().hasPermission(perm));
        } catch (final ResolveException e) {
            if (e.hasKey()) {
                messages.send(ctx.sender(), e.key(), e.args());
            } else {
                messages.send(ctx.sender(), MessageKey.ERROR_RESOLVE, Arg.s("error", e.getMessage()));
            }
            return;
        }
        lift.lift(ctx.sender(), ctx.integer("id"), resolved);
    }

    /**
     * Builds one lift-kind branch: {@code <kind>} and {@code <kind> <spec>}.
     * Kind literals bind before the greedy spec argument by the framework's
     * literal-first resolution, so the bare form defaults to vacate.
     */
    private Node liftKindBranch(final String literal, final LiftKind kind) {
        return Node.literal(literal)
                .executes(ctx -> doLift(ctx, kind, ""))
                .then(Node.argument("spec", Args.greedy())
                        .executes(ctx -> doLift(ctx, kind, ctx.str("spec"))));
    }

    /**
     * Builds one bulk-lift branch. The bare form previews the affected count;
     * execution requires the explicit {@code confirm} literal, with an
     * optional trailing reason. The two-step shape is deliberate: a bulk lift
     * is irreversible at scale and must never fire from a mistyped value.
     */
    private Node bulkBranch(final String literal, final Storage.BulkCriterion criterion) {
        return Node.literal(literal)
                .then(Node.argument("value", Args.word())
                        .executes(ctx -> lift.previewBulk(ctx.sender(), criterion, ctx.str("value")))
                        .then(Node.literal("confirm")
                                .executes(ctx -> lift.bulkLift(ctx.sender(), criterion,
                                        ctx.str("value"), null))
                                .then(Node.argument("reason", Args.greedy())
                                        .executes(ctx -> lift.bulkLift(ctx.sender(), criterion,
                                                ctx.str("value"), ctx.str("reason"))))));
    }

    // ----------------------------------------------------------------------
    // Info
    // ----------------------------------------------------------------------

    private void info(final Ctx ctx) {
        final long id = ctx.integer("id");
        final CommandSender to = ctx.sender();
        storage.findSanction(id).thenAccept(view -> {
            if (view == null) {
                messages.send(to, MessageKey.LIFT_NOT_FOUND, Arg.n("id", id));
                return;
            }
            renderInfo(to, view);
        });
    }

    private void renderInfo(final CommandSender to, final SanctionView v) {
        messages.send(to, MessageKey.INFO_HEADER, Arg.n("id", v.id()));
        messages.send(to, MessageKey.LINE_TARGET, Arg.s("target", nameOf(v.uuid())));
        messages.send(to, MessageKey.LINE_MEASURE, Arg.measure(v.measure()));
        messages.send(to, MessageKey.LINE_RULE, Arg.s("rule", v.rule() != null ? v.rule() : "-"));
        messages.send(to, MessageKey.LINE_REASON, Arg.reason(reasonData(to, v)));
        messages.send(to, MessageKey.LINE_DURATION, Arg.s("duration", durationOf(to, v)));
        messages.send(to, MessageKey.INFO_STATUS, Arg.s("status", statusOf(to, v)));
        messages.send(to, MessageKey.INFO_STAFF, Arg.s("staff", v.staff()));
        if (v.parentId() != 0L) {
            messages.send(to, MessageKey.DOCKET_WOVEN, Arg.n("parent", v.parentId()));
        }
    }

    private ReasonData reasonData(final CommandSender to, final SanctionView v) {
        return new ReasonData(
                v.reason(),
                v.measure(),
                v.rule() != null ? v.rule() : "-",
                durationOf(to, v),
                v.staff(),
                WHEN.format(Instant.ofEpochMilli(v.issuedAt())));
    }

    private String durationOf(final CommandSender to, final SanctionView v) {
        final Measure measure;
        try {
            measure = Measure.parse(v.measure());
        } catch (final IllegalArgumentException e) {
            return messages.plain(to, MessageKey.DURATION_NONE);
        }
        if (measure.temporal() == Measure.Temporal.INSTANTANEOUS) {
            return messages.plain(to, MessageKey.DURATION_NONE);
        }
        if (v.expiresAt() == 0L) {
            return messages.plain(to, MessageKey.DURATION_PERMANENT);
        }
        final long remaining = v.expiresAt() - System.currentTimeMillis();
        if (remaining <= 0L) {
            return messages.plain(to, MessageKey.DURATION_EXPIRED);
        }
        return messages.durationText(messages.localeTagFor(to), new DurationSpec(remaining, false));
    }

    private String statusOf(final CommandSender to, final SanctionView v) {
        if (v.liftedAt() != 0L) {
            return messages.plain(to, MessageKey.STATUS_LIFTED,
                    Arg.s("actor", v.liftedBy() != null ? v.liftedBy() : "?"),
                    Arg.key("kind", MessageKey.liftKindKey(LiftKind.fromCode(v.liftKind()))));
        }
        if (v.active() == 1) {
            return messages.plain(to, MessageKey.STATUS_ACTIVE);
        }
        if (v.stayed() == 1) {
            return messages.plain(to, MessageKey.STATUS_STAYED);
        }
        return messages.plain(to, MessageKey.STATUS_INACTIVE);
    }

    private String nameOf(final String uuid) {
        final OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(uuid));
        return op.getName() != null ? op.getName() : uuid;
    }

    private void docket(final Ctx ctx, final int page) {
        final String name = ctx.str("player");
        final CommandSender to = ctx.sender();
        final org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
        if (op == null) {
            messages.send(to, MessageKey.DOCKET_UNKNOWN, Arg.s("player", name));
            return;
        }
        final int perPage = 8;
        final int offset = (page - 1) * perPage;
        final String uuid = op.getUniqueId().toString();

        storage.countSanctions(uuid).thenCompose(total ->
                        storage.listSanctions(uuid, perPage, offset).thenApply(rows ->
                                new Object[]{total, rows}))
                .thenAccept(pair -> {
                    @SuppressWarnings("unchecked")
                    final int total = (int) pair[0];
                    @SuppressWarnings("unchecked")
                    final List<SanctionView> rows = (List<SanctionView>) pair[1];
                    final int pages = Math.max(1, (total + perPage - 1) / perPage);

                    messages.send(to, MessageKey.DOCKET_HEADER,
                            Arg.s("player", name), Arg.n("total", total),
                            Arg.n("page", page), Arg.n("pages", pages));
                    if (rows.isEmpty()) {
                        messages.send(to, MessageKey.DOCKET_EMPTY);
                        return;
                    }
                    for (final SanctionView v : rows) {
                        messages.send(to, MessageKey.DOCKET_ENTRY,
                                Arg.n("id", v.id()),
                                Arg.measure(v.measure()),
                                Arg.s("rule", v.rule() != null ? v.rule() : "-"),
                                Arg.s("status", statusOf(to, v)),
                                Arg.reason(reasonData(to, v)));
                        if (v.parentId() != 0L) {
                            messages.send(to, MessageKey.DOCKET_WOVEN, Arg.n("parent", v.parentId()));
                        }
                    }
                });
    }

    private void appeals(final Ctx ctx, final int page) {
        final CommandSender to = ctx.sender();
        final int perPage = 8;
        final int offset = (page - 1) * perPage;
        storage.countPendingAppeals().thenCompose(total ->
                        storage.listPendingAppeals(perPage, offset).thenApply(rows ->
                                new Object[]{total, rows}))
                .thenAccept(pair -> {
                    final int total = (int) pair[0];
                    @SuppressWarnings("unchecked")
                    final List<AppealView> rows = (List<AppealView>) pair[1];
                    final int pages = Math.max(1, (total + perPage - 1) / perPage);
                    messages.send(to, MessageKey.APPEALS_HEADER,
                            Arg.n("total", total), Arg.n("page", page), Arg.n("pages", pages));
                    if (rows.isEmpty()) {
                        messages.send(to, MessageKey.APPEALS_EMPTY);
                        return;
                    }
                    for (final AppealView a : rows) {
                        messages.send(to, MessageKey.APPEALS_ENTRY,
                                Arg.n("appeal", a.id()), Arg.n("id", a.banId()),
                                Arg.s("appellant", a.appellant()), Arg.s("text", a.text()));
                    }
                });
    }

    private void decide(final Ctx ctx, final String status, final String reason) {
        final long appealId = ctx.integer("id");
        final CommandSender actor = ctx.sender();
        storage.findAppeal(appealId).thenAccept(appeal -> {
            if (appeal == null) {
                messages.send(actor, MessageKey.APPEAL_NOT_FOUND, Arg.n("id", appealId));
                return;
            }
            if (!"PENDING".equals(appeal.status())) {
                messages.send(actor, MessageKey.APPEAL_ALREADY_DECIDED, Arg.n("id", appealId));
                return;
            }
            storage.decideAppeal(appealId, status, reason, actor.getName(), System.currentTimeMillis())
                    .thenAccept(rows -> {
                        if (rows == 0) {
                            messages.send(actor, MessageKey.APPEAL_ALREADY_DECIDED, Arg.n("id", appealId));
                            return;
                        }
                        final boolean accepted = "ACCEPTED".equals(status);
                        messages.send(actor, accepted ? MessageKey.APPEAL_ACCEPTED : MessageKey.APPEAL_DENIED,
                                Arg.n("id", appealId));
                        if (accepted) {
                            // An accepted appeal asserts the sanction was
                            // wrongly issued or excessive; the lift is a
                            // vacatur and the record leaves precedent.
                            lift.lift(actor, appeal.banId(), LiftKind.VACATE,
                                    reason != null ? reason
                                            : messages.plain(actor, MessageKey.APPEAL_LIFT_REASON));
                        }
                    });
        });
    }

    private List<String> completePlayers(final CompleteCtx ctx) {
        final List<String> names = new ArrayList<>();
        for (final Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        return names;
    }

    // ----------------------------------------------------------------------
    // Help and completion
    // ----------------------------------------------------------------------

    private void help(final Ctx ctx) {
        messages.send(ctx.sender(), MessageKey.ENFORCE_HELP,
                Arg.s("measures", measureList()),
                Arg.s("layouts", String.join(", ", layouts.keys())));
    }

    /*
    private List<String> complete(final CompleteCtx ctx) {

        final String p = ctx.partial();
        final List<String> out = new ArrayList<>(32);

        if (p.startsWith("::")) {
            for (final String key : layouts.keys()) {
                out.add("::" + key);
            }
            return out;
        }
        if (p.startsWith("@") || p.startsWith("!@")) {
            final int paren = p.indexOf('(');
            if (paren >= 0) {
                final String prefix = p.substring(0, paren);
                final String bare = prefix.startsWith("!@") ? prefix.substring(2) : prefix.substring(1);
                if (bare.equals("measure") || bare.equals("commute")) {
                    for (final Measure m : Measure.values()) {
                        out.add(prefix + "(" + m.name() + ")");
                    }
                }
                return out;
            }
            final String sigil = p.startsWith("!@") ? "!@" : "@";
            for (final AnnotationSpec spec : annotations.catalog().all()) {
                out.add(sigil + spec.name());
            }
            return out;
        }
        for (final Player player : Bukkit.getOnlinePlayers()) {
            out.add(player.getName());
        }
        return out;
    }
    */

    private String measureList() {
        final StringBuilder sb = new StringBuilder();
        for (final Measure m : Measure.values()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(m.name());
        }
        return sb.toString();
    }
}