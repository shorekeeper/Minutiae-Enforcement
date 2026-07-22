package org.synergyst.minutiae.command;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.synergyst.minutiae.annotation.AnnotationRegistry;
import org.synergyst.minutiae.annotation.AnnotationSpec;
import org.synergyst.minutiae.command.dsl.Args;
import org.synergyst.minutiae.command.dsl.Ctx;
import org.synergyst.minutiae.command.dsl.Node;
import org.synergyst.minutiae.fingerprint.FingerprintService;
import org.synergyst.minutiae.fingerprint.Session;
import org.synergyst.minutiae.fingerprint.SignalCollector;
import org.synergyst.minutiae.fingerprint.SignalType;
import org.synergyst.minutiae.layout.Layout;
import org.synergyst.minutiae.layout.LayoutRegistry;
import org.synergyst.minutiae.annotation.RawAnnotation;
import org.synergyst.minutiae.lifecycle.ReloadManager;
import org.synergyst.minutiae.measure.Measure;
import org.synergyst.minutiae.message.Arg;
import org.synergyst.minutiae.message.MessageKey;
import org.synergyst.minutiae.message.MessageService;
import org.synergyst.minutiae.message.Ph;
import org.synergyst.minutiae.preference.PreferenceService;
import org.synergyst.minutiae.rule.RuleRegistry;
import org.synergyst.minutiae.storage.Storage;
import org.synergyst.minutiae.time.DurationSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Diagnostic command tree exposing subsystem state.
 *
 * <p>Root literal {@code me-ban} with subcommands for storage liveness, rule,
 * layout, annotation, and fingerprint inspection. Every subcommand is
 * permission-gated and renders its output through the message service, so
 * diagnostic text is localisable and configurable alongside enforcement text.
 * Storage and fingerprint probes are asynchronous; their results are delivered
 * on completion.
 */
public final class DiagnosticCommand {

    private static final String PERMISSION_PING = "minutiae.command.ping";
    private static final String PERMISSION_RULES = "minutiae.command.rules";
    private static final String PERMISSION_LAYOUTS = "minutiae.command.layouts";
    private static final String PERMISSION_ANNOTATIONS = "minutiae.command.annotations";
    private static final String PERMISSION_FINGERPRINT = "minutiae.command.fingerprint";

    private final Storage storage;
    private final RuleRegistry rules;
    private final LayoutRegistry layouts;
    private final AnnotationRegistry annotations;
    private final FingerprintService fingerprint;
    private final MessageService messages;
    private final ReloadManager reloads;
    private final JavaPlugin plugin;

    private static final String PERMISSION_VERBOSE = "minutiae.command.verbose";

    private final PreferenceService preferences;

    public DiagnosticCommand(final Storage storage,
                             final RuleRegistry rules,
                             final LayoutRegistry layouts,
                             final AnnotationRegistry annotations,
                             final FingerprintService fingerprint,
                             final MessageService messages,
                             final PreferenceService preferences,
                             final ReloadManager reloads,
                             final JavaPlugin plugin) {
        this.storage = storage;
        this.rules = rules;
        this.layouts = layouts;
        this.annotations = annotations;
        this.fingerprint = fingerprint;
        this.messages = messages;
        this.preferences = preferences;
        this.reloads = reloads;
        this.plugin = plugin;
    }

    /**
     * Builds the command tree.
     *
     * @return the root node
     */
    public Node tree() {
        return Node.literal("me-ban")
                .then(Node.literal("ping")
                        .permission(PERMISSION_PING)
                        .executes(this::ping))
                .then(Node.literal("rules")
                        .permission(PERMISSION_RULES)
                        .executes(this::ruleSummary)
                        .then(Node.argument("id", Args.greedy())
                                .executes(this::ruleLookup)))
                .then(Node.literal("layouts")
                        .permission(PERMISSION_LAYOUTS)
                        .executes(this::layoutSummary)
                        .then(Node.argument("key", Args.word())
                                .executes(this::layoutInspect)))
                .then(Node.literal("annotations")
                        .permission(PERMISSION_ANNOTATIONS)
                        .executes(this::annotationSummary)
                        .then(Node.argument("name", Args.word())
                                .executes(this::annotationInspect)))
                .then(Node.literal("verbose")
                        .permission(PERMISSION_VERBOSE)
                        .executes(this::verboseShow)
                        .then(Node.argument("state", Args.word())
                                .executes(this::verboseSet)))
                .then(Node.literal("reload")
                        .permission("minutiae.command.reload")
                        .executes(this::reload))
                .then(Node.literal("fingerprint")
                        .permission(PERMISSION_FINGERPRINT)
                        .then(Node.argument("name", Args.word())
                                .suggests(ctx -> {
                                    final List<String> names = new ArrayList<>();
                                    for (final Player p : Bukkit.getOnlinePlayers()) {
                                        names.add(p.getName());
                                    }
                                    return names;
                                })
                                .executes(this::fingerprintInspect)));
    }

    private void ping(final Ctx ctx) {
        messages.send(ctx.sender(), MessageKey.DIAG_PING_PROBING);
        storage.ping().thenAccept(result -> {
            if (result.ok()) {
                final String latency = String.format(Locale.ROOT, "%.3f",
                        result.roundTripNs() / 1_000_000.0d);
                messages.send(ctx.sender(), MessageKey.DIAG_PING_OK,
                        Arg.s("latency", latency), Arg.n("schema", result.schemaVersion()));
            } else {
                messages.send(ctx.sender(), MessageKey.DIAG_PING_UNAVAILABLE);
            }
        });
    }

    private void ruleSummary(final Ctx ctx) {
        messages.send(ctx.sender(), MessageKey.DIAG_RULES_COUNT, Arg.n("count", rules.size()));
    }

    private void ruleLookup(final Ctx ctx) {
        final String id = ctx.str("id").trim();
        final String description = rules.describe(id);
        if (description == null) {
            messages.send(ctx.sender(), MessageKey.DIAG_RULES_UNDEFINED, Arg.s("id", id));
        } else {
            messages.send(ctx.sender(), MessageKey.DIAG_RULES_ENTRY,
                    Arg.s("id", id), Arg.s("description", description));
        }
    }

    private void layoutSummary(final Ctx ctx) {
        messages.send(ctx.sender(), MessageKey.DIAG_LAYOUTS_LIST,
                Arg.n("count", layouts.size()), Arg.s("keys", String.join(", ", layouts.keys())));
    }

    private void layoutInspect(final Ctx ctx) {
        final String key = ctx.str("key");
        final Layout layout = layouts.get(key);
        if (layout == null) {
            messages.send(ctx.sender(), MessageKey.DIAG_LAYOUTS_UNKNOWN, Arg.s("key", key));
            return;
        }

        final String ruleDesc = rules.describe(layout.rule());
        messages.send(ctx.sender(), MessageKey.DIAG_LAYOUT_HEADER, Arg.s("key", layout.key()));
        messages.send(ctx.sender(), MessageKey.DIAG_LAYOUT_MEASURE,
                Arg.measure(layout.measure().name()));
        messages.send(ctx.sender(), MessageKey.DIAG_LAYOUT_RULE,
                Arg.s("rule", layout.rule()), Arg.s("description", ruleDesc != null ? ruleDesc : "-"));
        messages.send(ctx.sender(), MessageKey.DIAG_LAYOUT_REASON,
                Arg.s("reason", layout.reason() != null ? layout.reason()
                        : messages.plain(ctx.sender(), MessageKey.DIAG_LAYOUT_REASON_INHERITED)));
        messages.send(ctx.sender(), MessageKey.DIAG_LAYOUT_DURATION,
                Arg.s("duration", layout.duration() != null ? layout.duration().format()
                        : messages.plain(ctx.sender(),
                        layout.measure().temporal() == Measure.Temporal.PERMANENT
                                ? MessageKey.DURATION_PERMANENT
                                : MessageKey.DURATION_NONE)));

        if (layout.escalation().length > 0) {
            final StringBuilder ladder = new StringBuilder();
            final DurationSpec[] esc = layout.escalation();
            for (int i = 0; i < esc.length; i++) {
                if (i > 0) {
                    ladder.append(" -> ");
                }
                ladder.append(esc[i].format());
            }
            messages.send(ctx.sender(), MessageKey.DIAG_LAYOUT_ESCALATION, Arg.s("ladder", ladder.toString()));
        }
        if (layout.annotations().length > 0) {
            messages.send(ctx.sender(), MessageKey.DIAG_LAYOUT_FLAGS,
                    Arg.s("flags", joinAnnotations(layout.annotations())));
        }
    }

    private void annotationSummary(final Ctx ctx) {
        final AnnotationSpec[] all = annotations.catalog().all();
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('@').append(all[i].name());
        }
        messages.send(ctx.sender(), MessageKey.DIAG_ANNOTATIONS_LIST,
                Arg.n("count", all.length), Arg.s("names", sb.toString()));
    }

    private void annotationInspect(final Ctx ctx) {
        final String name = ctx.str("name");
        final AnnotationSpec spec = annotations.catalog().byName(name);
        if (spec == null) {
            messages.send(ctx.sender(), MessageKey.DIAG_ANNOTATION_UNKNOWN, Arg.s("name", name));
            return;
        }
        final var matrix = annotations.matrix();
        final long implied = matrix.closureOf(spec.ordinal()) & ~spec.bit();

        messages.send(ctx.sender(), MessageKey.DIAG_ANNOTATION_HEADER, Arg.s("name", spec.name()));
        messages.send(ctx.sender(), MessageKey.DIAG_ANNOTATION_SCOPE, Arg.s("scope", spec.scope().name()));
        messages.send(ctx.sender(), MessageKey.DIAG_ANNOTATION_PERMISSION,
                Arg.s("permission", spec.permission()));
        messages.send(ctx.sender(), MessageKey.DIAG_ANNOTATION_IMPLIES, Arg.s("names", joinNames(implied)));
        messages.send(ctx.sender(), MessageKey.DIAG_ANNOTATION_REQUIRES,
                Arg.s("names", joinNames(matrix.requiresOf(spec.ordinal()))));
        messages.send(ctx.sender(), MessageKey.DIAG_ANNOTATION_CONFLICTS,
                Arg.s("names", joinNames(matrix.conflictsOf(spec.ordinal()))));
    }

    private void verboseShow(final Ctx ctx) {
        final boolean v = preferences.isVerbose(ctx.sender());
        messages.send(ctx.sender(), v ? MessageKey.PREF_VERBOSE_ON : MessageKey.PREF_VERBOSE_OFF);
    }

    private void verboseSet(final Ctx ctx) {
        if (!(ctx.sender() instanceof Player player)) {
            messages.send(ctx.sender(), MessageKey.PREF_CONSOLE);
            return;
        }
        final String s = ctx.str("state").toLowerCase(java.util.Locale.ROOT);
        final boolean value = s.equals("on") || s.equals("true") || s.equals("yes");
        preferences.setVerbose(player, value);
        messages.send(player, value ? MessageKey.PREF_VERBOSE_ON : MessageKey.PREF_VERBOSE_OFF);
    }

    private void fingerprintInspect(final Ctx ctx) {
        final String name = ctx.str("name");
        final Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            messages.send(ctx.sender(), MessageKey.FINGERPRINT_NOT_ONLINE, Arg.s("name", name));
            return;
        }

        final Session session = fingerprint.sessions().get(player.getUniqueId());
        messages.send(ctx.sender(), MessageKey.FINGERPRINT_HEADER, Arg.s("name", name));
        if (session == null) {
            messages.send(ctx.sender(), MessageKey.FINGERPRINT_NO_SESSION);
        } else {
            signalLine(ctx, SignalType.IP_FULL.configKey(), SignalCollector.fullIp(session.address()));
            signalLine(ctx, SignalType.IP_SUBNET.configKey(), SignalCollector.subnet(session.address()));
            signalLine(ctx, SignalType.LOCALE.configKey(), session.locale());
            signalLine(ctx, SignalType.CLIENT_BRAND.configKey(), session.brand());
            signalLine(ctx, SignalType.NAME_PATTERN.configKey(),
                    SignalCollector.namePattern(session.name()));
        }

        fingerprint.scorePlayer(player).thenAccept(match -> {
            if (match == null) {
                messages.send(ctx.sender(), MessageKey.FINGERPRINT_NO_MATCH);
            } else {
                final String score = String.format(Locale.ROOT, "%.2f", match.score());
                messages.send(ctx.sender(), MessageKey.FINGERPRINT_MATCH,
                        Arg.s("banned", match.bannedUuid()), Arg.s("score", score),
                        Arg.n("signals", match.matchedSignals()));
            }
        });
    }

    private void signalLine(final Ctx ctx, final String label, final String value) {
        messages.send(ctx.sender(), MessageKey.FINGERPRINT_LINE,
                Arg.s("label", label), Arg.s("value", value != null ? value : "-"));
    }

    private String joinAnnotations(final RawAnnotation[] arr) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(arr[i].toDisplay());
        }
        return sb.toString();
    }

    private String joinNames(final long mask) {
        if (mask == 0L) {
            return "-";
        }
        final List<String> names = annotations.namesOf(mask);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append('@').append(names.get(i));
        }
        return sb.toString();
    }

    private void reload(final Ctx ctx) {
        plugin.reloadConfig();
        final ReloadManager.Result result = reloads.reloadAll();
        messages.send(ctx.sender(), MessageKey.RELOAD_DONE,
                Arg.n("ok", result.succeeded()), Arg.n("failed", result.failed()));
    }
}