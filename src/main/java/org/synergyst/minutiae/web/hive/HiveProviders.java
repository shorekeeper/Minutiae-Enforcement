package org.synergyst.minutiae.web.hive;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.synergyst.minutiae.behaviour.Behaviour;
import org.synergyst.minutiae.fingerprint.FingerprintService;
import org.synergyst.minutiae.measure.Measure;
import org.synergyst.minutiae.rule.RuleRegistry;
import org.synergyst.minutiae.storage.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The projection logic behind every hive.
 *
 * <p>The cases hive expresses sanction provenance as hierarchy: a subject
 * descends into its sanctions, grouped by measure, then by the enforcer who
 * imposed each, then into the individual sanction, whose own subtree carries its
 * cited provisions, captured signals, amendments, woven children, and audit
 * trail. Where a subject was sanctioned with the same measure by several
 * enforcers, each enforcer is a distinct branch; where an enforcer imposed the
 * measure more than once, each imposition is a distinct branch. Nothing is
 * cross-linked laterally: to follow who sanctioned whom, one descends the tree.
 *
 * <p>The appeals, amends, and system hives collect their respective concerns in
 * the same descending fashion. All rows are drawn read-only from the
 * authoritative storage layer and the live automaton engine; no method mutates
 * state.
 */
public final class HiveProviders {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final int PAGE = 250;
    private static final int PER_SUBJECT = 500;

    private final Storage storage;
    private final RuleRegistry rules;
    private final FingerprintService fingerprint;
    private final JavaPlugin plugin;
    private final long startMillis;
    private static final long CLUSTER_TTL_MS = 30_000L;
    private static final int CLUSTER_LINK_CAP = 50_000;

    private final Object clusterLock = new Object();
    private volatile ClusterIndex clusterCache;

    private final org.synergyst.minutiae.name.NameService names;

    public HiveProviders(final Storage storage,
                         final RuleRegistry rules,
                         final FingerprintService fingerprint,
                         final org.synergyst.minutiae.name.NameService names,
                         final JavaPlugin plugin,
                         final long startMillis) {
        this.storage = storage;
        this.rules = rules;
        this.fingerprint = fingerprint;
        this.names = names;
        this.plugin = plugin;
        this.startMillis = startMillis;
    }

    // ----------------------------------------------------------------------
    // Root
    // ----------------------------------------------------------------------

    /** Resolves the root: the enumeration of hives. */
    public CompletableFuture<HiveKey> root(final HivePath path) {
        return done(new HiveKey(HivePath.ROOT, "Genuine", List.of(
                folder(HiveNames.CASES),
                folder(HiveNames.APPEALS),
                folder(HiveNames.AMENDS),
                folder(HiveNames.FINGERPRINTS),
                folder(HiveNames.SYSTEM)),
                List.of()));
    }

    // ----------------------------------------------------------------------
    // HKEY_CASES : Players / <subject> / Sanctions / <MEASURE> / <staff> / #<id>
    // ----------------------------------------------------------------------

    /** Resolves the cases hive. */
    public CompletableFuture<HiveKey> cases(final HivePath path) {
        if (path.depth() == 1) {
            return done(new HiveKey(path, HiveNames.CASES, List.of(folder("Players")), List.of()));
        }
        if (!path.seg(1).equals("Players")) {
            return done(null);
        }
        if (path.depth() == 2) {
            return storage.distinctSubjects(PAGE, 0).thenApply(uuids -> {
                final List<HiveChild> kids = new ArrayList<>(uuids.size());
                for (final String uuid : uuids) {
                    kids.add(new HiveChild(uuid, offlineName(uuid), true));
                }
                return new HiveKey(path, "Players", kids, List.of(i("Subjects", uuids.size())));
            });
        }
        final String uuid = path.seg(2);
        if (path.depth() == 3) {
            return done(new HiveKey(path, offlineName(uuid), List.of(
                    folder("Sanctions"), folder("Lifts"), folder("Joinders")),
                    List.of(v("Name", offlineName(uuid)), v("UUID", uuid))));
        }
        return switch (path.seg(3)) {
            case "Sanctions" -> sanctionsBranch(path, uuid);
            case "Lifts" -> liftsBranch(path, uuid);
            case "Joinders" -> joindersBranch(path, uuid);
            default -> done(null);
        };
    }

    private CompletableFuture<HiveKey> sanctionsBranch(final HivePath path, final String uuid) {
        return storage.listSanctions(uuid, PER_SUBJECT, 0).thenCompose(all -> {
            // Depth 4: the measures under which this subject holds any sanction.
            if (path.depth() == 4) {
                final List<HiveChild> kids = new ArrayList<>();
                for (final Measure m : Measure.values()) {
                    if (all.stream().anyMatch(v -> m.name().equals(v.measure()))) {
                        kids.add(folder(m.name()));
                    }
                }
                return done(new HiveKey(path, "Sanctions", kids, List.of(i("Total", all.size()))));
            }
            final String measure = path.seg(4);
            final List<SanctionView> byMeasure =
                    all.stream().filter(v -> measure.equals(v.measure())).toList();

            // Depth 5: the enforcers who imposed this measure on this subject.
            if (path.depth() == 5) {
                final Set<String> staff = new LinkedHashSet<>();
                for (final SanctionView v : byMeasure) {
                    staff.add(v.staff());
                }
                final List<HiveChild> kids = new ArrayList<>(staff.size());
                for (final String s : staff) {
                    kids.add(folder(s));
                }
                return done(new HiveKey(path, measure, kids, List.of(i("Count", byMeasure.size()))));
            }
            final String staff = path.seg(5);
            final List<SanctionView> byStaff =
                    byMeasure.stream().filter(v -> staff.equals(v.staff())).toList();

            // Depth 6: each individual sanction this enforcer imposed.
            if (path.depth() == 6) {
                final List<HiveChild> kids = new ArrayList<>(byStaff.size());
                for (final SanctionView v : byStaff) {
                    kids.add(new HiveChild(String.valueOf(v.id()), "#" + v.id() + "  " + status(v), true));
                }
                return new CompletableFuture<HiveKey>() {{
                    complete(new HiveKey(path, staff, kids, List.of(i("Count", byStaff.size()))));
                }};
            }

            // Depth 7+: the sanction itself and its subtree.
            final long id = num(path.seg(6));
            return sanctionSubtree(path, id, 7);
        });
    }

    private CompletableFuture<HiveKey> liftsBranch(final HivePath path, final String uuid) {
        return storage.listSanctions(uuid, PER_SUBJECT, 0).thenCompose(all -> {
            final List<SanctionView> lifted = all.stream().filter(v -> v.liftedAt() != 0L).toList();
            if (path.depth() == 4) {
                final List<HiveChild> kids = new ArrayList<>(lifted.size());
                for (final SanctionView v : lifted) {
                    kids.add(new HiveChild(String.valueOf(v.id()),
                            "#" + v.id() + "  " + v.measure() + "  by " + safe(v.liftedBy()), false));
                }
                return done(new HiveKey(path, "Lifts", kids, List.of(i("Count", lifted.size()))));
            }
            final long id = num(path.seg(4));
            return storage.findSanction(id).thenApply(v ->
                    v == null ? null : new HiveKey(path, "#" + id, List.of(), sanctionValues(v)));
        });
    }

    private CompletableFuture<HiveKey> joindersBranch(final HivePath path, final String uuid) {
        return storage.listSanctions(uuid, PER_SUBJECT, 0).thenCompose(all -> {
            final List<SanctionView> woven = all.stream().filter(v -> v.parentId() != 0L).toList();
            if (path.depth() == 4) {
                final List<HiveChild> kids = new ArrayList<>(woven.size());
                for (final SanctionView v : woven) {
                    kids.add(new HiveChild(String.valueOf(v.id()),
                            "#" + v.id() + "  under #" + v.parentId(), false));
                }
                return done(new HiveKey(path, "Joinders", kids, List.of(i("Count", woven.size()))));
            }
            final long id = num(path.seg(4));
            return storage.findSanction(id).thenApply(v ->
                    v == null ? null : new HiveKey(path, "#" + id, List.of(), sanctionValues(v)));
        });
    }

    /**
     * Resolves a sanction and its subtree. {@code base} is the path depth at
     * which the sanction identifier's node begins.
     */
    private CompletableFuture<HiveKey> sanctionSubtree(final HivePath path, final long id, final int base) {
        return storage.findSanction(id).thenCompose(v -> {
            if (v == null) {
                return done(null);
            }
            if (path.depth() == base) {
                return done(new HiveKey(path, "#" + id, List.of(
                        folder("Provisions"), folder("Signals"), folder("Transcript"),
                        folder("Rationale"), folder("Amends"), folder("Weaves"), folder("Trail")),
                        sanctionValues(v)));
            }
            final String folder = path.seg(base);
            return switch (folder) {
                case "Provisions" -> provisions(path, id, base + 1);
                case "Signals" -> signals(path, id, base + 1);
                case "Amends" -> amendsOf(path, id, base + 1);
                case "Weaves" -> weavesOf(path, id, base + 1);
                case "Trail" -> trailOf(path, id, base + 1);
                case "Transcript" -> transcript(path, id);
                case "Rationale" -> rationale(path, id);
                default -> done(null);
            };
        });
    }

    private CompletableFuture<HiveKey> provisions(final HivePath path, final long id, final int itemDepth) {
        return storage.listProvisions(id).thenApply(list -> {
            if (path.depth() == itemDepth) {
                final List<HiveChild> kids = new ArrayList<>(list.size());
                for (final String r : list) {
                    kids.add(new HiveChild(r, null, false));
                }
                return new HiveKey(path, "Provisions", kids, List.of(i("Count", list.size())));
            }
            final String r = path.seg(itemDepth);
            return list.contains(r)
                    ? new HiveKey(path, r, List.of(), List.of(v("Rule", r), v("Text", safe(rules.describe(r)))))
                    : null;
        });
    }

    private CompletableFuture<HiveKey> signals(final HivePath path, final long id, final int itemDepth) {
        return storage.listSignals(id).thenApply(list -> {
            if (path.depth() == itemDepth) {
                final List<HiveChild> kids = new ArrayList<>(list.size());
                for (int n = 0; n < list.size(); n++) {
                    kids.add(new HiveChild(String.valueOf(n),
                            org.synergyst.minutiae.fingerprint.SignalType.fromCode(list.get(n).type())
                                    .configKey(), false));
                }
                return new HiveKey(path, "Signals", kids, List.of(i("Count", list.size())));
            }
            final int n = (int) num(path.seg(itemDepth));
            if (n < 0 || n >= list.size()) {
                return null;
            }
            final SignalRow r = list.get(n);
            return new HiveKey(path, String.valueOf(n), List.of(), List.of(
                    v("Type", org.synergyst.minutiae.fingerprint.SignalType.fromCode(r.type()).configKey()),
                    v("Value", r.value()),
                    v("Weight", String.format(java.util.Locale.ROOT, "%.3f", r.weight()))));
        });
    }

    private CompletableFuture<HiveKey> amendsOf(final HivePath path, final long id, final int itemDepth) {
        return storage.listAudit(id, PAGE, 0).thenApply(rows -> {
            final List<AuditRow> amends = rows.stream().filter(r -> "AMEND".equals(r.action())).toList();
            if (path.depth() == itemDepth) {
                final List<HiveChild> kids = new ArrayList<>(amends.size());
                for (final AuditRow r : amends) {
                    kids.add(new HiveChild(String.valueOf(r.id()), "by " + r.actor(), false));
                }
                return new HiveKey(path, "Amends", kids, List.of(i("Count", amends.size())));
            }
            final long aid = num(path.seg(itemDepth));
            for (final AuditRow r : amends) {
                if (r.id() == aid) {
                    return auditKey(path, r);
                }
            }
            return null;
        });
    }

    private CompletableFuture<HiveKey> weavesOf(final HivePath path, final long id, final int itemDepth) {
        return storage.chainNodes(id).thenCompose(nodes -> {
            final List<SanctionView> children = nodes.stream().filter(v -> v.parentId() == id).toList();
            if (path.depth() == itemDepth) {
                final List<HiveChild> kids = new ArrayList<>(children.size());
                for (final SanctionView v : children) {
                    kids.add(new HiveChild(String.valueOf(v.id()),
                            "#" + v.id() + "  " + v.measure(), false));
                }
                return done(new HiveKey(path, "Weaves", kids, List.of(i("Count", children.size()))));
            }
            final long cid = num(path.seg(itemDepth));
            return storage.findSanction(cid).thenApply(v ->
                    v == null ? null : new HiveKey(path, "#" + cid, List.of(), sanctionValues(v)));
        });
    }

    private CompletableFuture<HiveKey> trailOf(final HivePath path, final long id, final int itemDepth) {
        return storage.listAudit(id, PAGE, 0).thenApply(rows -> {
            if (path.depth() == itemDepth) {
                final List<HiveChild> kids = new ArrayList<>(rows.size());
                for (final AuditRow r : rows) {
                    kids.add(new HiveChild(String.valueOf(r.id()), r.action() + "  " + r.actor(), false));
                }
                return new HiveKey(path, "Trail", kids, List.of(i("Entries", rows.size())));
            }
            final long aid = num(path.seg(itemDepth));
            for (final AuditRow r : rows) {
                if (r.id() == aid) {
                    return auditKey(path, r);
                }
            }
            return null;
        });
    }

    // ----------------------------------------------------------------------
    // HKEY_APPEALS : Players / <appellant> / #<id>   ·   Appeals / #<id>
    // ----------------------------------------------------------------------

    /** Resolves the appeals hive. */
    public CompletableFuture<HiveKey> appeals(final HivePath path) {
        if (path.depth() == 1) {
            return done(new HiveKey(path, HiveNames.APPEALS,
                    List.of(folder("Players"), folder("Appeals")), List.of()));
        }
        final String branch = path.seg(1);
        if (branch.equals("Players")) {
            return storage.listAppeals(PAGE, 0).thenApply(all -> {
                if (path.depth() == 2) {
                    final Set<String> people = new LinkedHashSet<>();
                    for (final AppealView a : all) {
                        people.add(a.appellant());
                    }
                    final List<HiveChild> kids = new ArrayList<>(people.size());
                    for (final String p : people) {
                        kids.add(folder(p));
                    }
                    return new HiveKey(path, "Players", kids, List.of(i("Appellants", people.size())));
                }
                final String who = path.seg(2);
                final List<AppealView> mine = all.stream().filter(a -> who.equals(a.appellant())).toList();
                if (path.depth() == 3) {
                    final List<HiveChild> kids = new ArrayList<>(mine.size());
                    for (final AppealView a : mine) {
                        kids.add(new HiveChild(String.valueOf(a.id()),
                                "#" + a.id() + "  " + a.status(), false));
                    }
                    return new HiveKey(path, who, kids, List.of(i("Count", mine.size())));
                }
                final long id = num(path.seg(3));
                for (final AppealView a : mine) {
                    if (a.id() == id) {
                        return appealKey(path, a);
                    }
                }
                return null;
            });
        }
        if (branch.equals("Appeals")) {
            if (path.depth() == 2) {
                return storage.listAppeals(PAGE, 0).thenApply(all -> {
                    final List<HiveChild> kids = new ArrayList<>(all.size());
                    for (final AppealView a : all) {
                        kids.add(new HiveChild(String.valueOf(a.id()),
                                "#" + a.id() + "  " + a.appellant() + "  " + a.status(), false));
                    }
                    return new HiveKey(path, "Appeals", kids, List.of(i("Total", all.size())));
                });
            }
            final long id = num(path.seg(2));
            return storage.findAppeal(id).thenApply(a -> a == null ? null : appealKey(path, a));
        }
        return done(null);
    }

    // ----------------------------------------------------------------------
    // HKEY_AMENDS : Amends / <auditId>   ·   Tracebacks / #<banId> / <auditId>
    // ----------------------------------------------------------------------

    /** Resolves the amends hive. */
    public CompletableFuture<HiveKey> amends(final HivePath path) {
        if (path.depth() == 1) {
            return done(new HiveKey(path, HiveNames.AMENDS,
                    List.of(folder("Amends"), folder("Tracebacks")), List.of()));
        }
        final String branch = path.seg(1);
        if (branch.equals("Amends")) {
            return storage.listAuditByAction("AMEND", PAGE, 0).thenApply(rows -> {
                if (path.depth() == 2) {
                    final List<HiveChild> kids = new ArrayList<>(rows.size());
                    for (final AuditRow r : rows) {
                        kids.add(new HiveChild(String.valueOf(r.id()),
                                (r.banId() == null ? "?" : "#" + r.banId()) + "  by " + r.actor(), false));
                    }
                    return new HiveKey(path, "Amends", kids, List.of(i("Count", rows.size())));
                }
                final long aid = num(path.seg(2));
                for (final AuditRow r : rows) {
                    if (r.id() == aid) {
                        return auditKey(path, r);
                    }
                }
                return null;
            });
        }
        if (branch.equals("Tracebacks")) {
            return storage.listAuditByAction("AMEND", 1000, 0).thenApply(rows -> {
                if (path.depth() == 2) {
                    final Set<Long> bans = new LinkedHashSet<>();
                    for (final AuditRow r : rows) {
                        if (r.banId() != null) {
                            bans.add(r.banId());
                        }
                    }
                    final List<HiveChild> kids = new ArrayList<>(bans.size());
                    for (final Long b : bans) {
                        kids.add(new HiveChild(String.valueOf(b), "#" + b, true));
                    }
                    return new HiveKey(path, "Tracebacks", kids, List.of(i("Sanctions", bans.size())));
                }
                final long banId = num(path.seg(2));
                final List<AuditRow> history = rows.stream()
                        .filter(r -> r.banId() != null && r.banId() == banId).toList();
                if (path.depth() == 3) {
                    final List<HiveChild> kids = new ArrayList<>(history.size());
                    for (final AuditRow r : history) {
                        kids.add(new HiveChild(String.valueOf(r.id()), "by " + r.actor(), false));
                    }
                    return new HiveKey(path, "#" + banId, kids, List.of(i("Revisions", history.size())));
                }
                final long aid = num(path.seg(3));
                for (final AuditRow r : history) {
                    if (r.id() == aid) {
                        return auditKey(path, r);
                    }
                }
                return null;
            });
        }
        return done(null);
    }

    // ----------------------------------------------------------------------
    // HKEY_SYSTEM : Automata / <name> / Rules   ·   Trail / <auditId>
    // ----------------------------------------------------------------------

    /** Resolves the system hive. */
    public CompletableFuture<HiveKey> system(final HivePath path) {
        if (path.depth() == 1) {
            return storage.stats().thenApply(st -> new HiveKey(path, HiveNames.SYSTEM,
                    List.of(folder("Automata"), folder("Trail")),
                    List.of(
                            i("SchemaVersion", storage.schemaVersion()),
                            v("Uptime", HiveFmt.durationMs(System.currentTimeMillis() - startMillis)),
                            i("SanctionsTotal", st.totalSanctions()),
                            i("SanctionsActive", st.activeSanctions()),
                            i("AppealsPending", st.pendingAppeals()),
                            i("Rules", st.totalRules()))));
        }
        final String branch = path.seg(1);

        if (branch.equals("Trail")) {
            return storage.listAudit(null, PAGE, 0).thenApply(rows -> {
                if (path.depth() == 2) {
                    final List<HiveChild> kids = new ArrayList<>(rows.size());
                    for (final AuditRow r : rows) {
                        kids.add(new HiveChild(String.valueOf(r.id()), r.action() + "  " + r.actor(), false));
                    }
                    return new HiveKey(path, "Trail", kids, List.of(i("Entries", rows.size())));
                }
                final long aid = num(path.seg(2));
                for (final AuditRow r : rows) {
                    if (r.id() == aid) {
                        return auditKey(path, r);
                    }
                }
                return null;
            });
        }
        return done(null);
    }

    private CompletableFuture<HiveKey> transcript(final HivePath path, final long id) {
        return storage.listTranscript(id).thenApply(rows -> {
            final String csv = org.synergyst.minutiae.chat.TranscriptCsv.render(rows);
            return new HiveKey(path, "Transcript", List.of(), List.of(
                    i("Lines", rows.size()),
                    new HiveValue("CSV", ValueType.CSV,
                            rows.size() + " line(s) — open", csv, null)));
        });
    }

    // ----------------------------------------------------------------------
    // Rationale
    // ----------------------------------------------------------------------

    private CompletableFuture<HiveKey> rationale(final HivePath path, final long id) {
        return storage.findTrace(id).thenApply(t -> {
            if (t == null) {
                return new HiveKey(path, "Rationale", List.of(),
                        List.of(enun("Status", "not recorded")));
            }
            final List<HiveValue> vals = new ArrayList<>(9);
            vals.add(enun("Status", "recorded"));
            vals.add(i("PriorSanctions", t.priorSanctions()));
            vals.add(i("PriorWarnings", t.priorWarnings()));
            vals.add(v("InProbation", t.inProbation() ? "true" : "false"));
            vals.add(v("Escalated", t.escalated() ? "true" : "false"));
            vals.add(t.ladderIndex() >= 0 ? i("LadderIndex", t.ladderIndex())
                    : v("LadderIndex", "-"));
            vals.add(v("BaseDuration",
                    t.baseMs() < 0 ? "permanent" : HiveFmt.durationMs(t.baseMs())));
            vals.add(v("FinalDuration",
                    t.finalMs() < 0 ? "permanent" : HiveFmt.durationMs(t.finalMs())));
            vals.add(v("WarnDowngrade", t.warnDowngrade() ? "true" : "false"));
            return new HiveKey(path, "Rationale", List.of(), vals);
        });
    }

    // ----------------------------------------------------------------------
    // HKEY_FINGERPRINTS
    // ----------------------------------------------------------------------

    /** Resolves the fingerprint forensic hive. */
    public CompletableFuture<HiveKey> fingerprints(final HivePath path) {
        if (path.depth() == 1) {
            return done(new HiveKey(path, HiveNames.FINGERPRINTS, List.of(
                    folder("ByType"), folder("Clusters"), folder("Suspects")), List.of()));
        }
        return switch (path.seg(1)) {
            case "ByType" -> byType(path);
            case "Clusters" -> clustersBranch(path);
            case "Suspects" -> suspects(path);
            default -> done(null);
        };
    }

    private CompletableFuture<HiveKey> byType(final HivePath path) {
        if (path.depth() == 2) {
            final List<HiveChild> kids = new ArrayList<>();
            for (final org.synergyst.minutiae.fingerprint.SignalType t
                    : org.synergyst.minutiae.fingerprint.SignalType.values()) {
                kids.add(folder(t.configKey()));
            }
            return done(new HiveKey(path, "ByType", kids, List.of()));
        }
        final org.synergyst.minutiae.fingerprint.SignalType type = typeByKey(path.seg(2));
        if (type == null) {
            return done(null);
        }
        if (path.depth() == 3) {
            return storage.distinctSignalValues(type.code(), PAGE, 0).thenApply(values -> {
                final List<HiveChild> kids = new ArrayList<>(values.size());
                for (final String value : values) {
                    kids.add(new HiveChild(encSeg(value), value, true));
                }
                return new HiveKey(path, type.configKey(), kids, List.of(i("Values", values.size())));
            });
        }
        final String value = decSeg(path.seg(3));
        if (value.isEmpty()) {
            return done(null);
        }
        if (path.depth() == 4) {
            return done(new HiveKey(path, value, List.of(
                    folder("Accounts"), folder("Sanctions")),
                    List.of(v("Type", type.configKey()), v("Value", value))));
        }
        return switch (path.seg(4)) {
            case "Accounts" -> signalAccounts(path, type, value);
            case "Sanctions" -> signalSanctions(path, type, value);
            default -> done(null);
        };
    }

    private CompletableFuture<HiveKey> signalAccounts(
            final HivePath path, final org.synergyst.minutiae.fingerprint.SignalType type,
            final String value) {
        return storage.accountsForSignalValue(type.code(), value).thenApply(list -> {
            if (path.depth() == 5) {
                final List<HiveChild> kids = new ArrayList<>(list.size());
                for (final String uuid : list) {
                    kids.add(new HiveChild(uuid, offlineName(uuid), true));
                }
                return new HiveKey(path, "Accounts", kids, List.of(i("Accounts", list.size())));
            }
            final String uuid = path.seg(5);
            if (!list.contains(uuid)) {
                return null;
            }
            return new HiveKey(path, offlineName(uuid), List.of(), List.of(
                    v("Name", offlineName(uuid)),
                    v("UUID", uuid),
                    new HiveValue("Case", ValueType.LINK, "open case tree", uuid, subjectPath(uuid))));
        });
    }

    private CompletableFuture<HiveKey> signalSanctions(
            final HivePath path, final org.synergyst.minutiae.fingerprint.SignalType type,
            final String value) {
        if (path.depth() == 5) {
            return storage.sanctionsForSignalValue(type.code(), value, PAGE, 0).thenApply(list -> {
                final List<HiveChild> kids = new ArrayList<>(list.size());
                for (final SanctionView v : list) {
                    kids.add(new HiveChild(String.valueOf(v.id()),
                            "#" + v.id() + "  " + v.measure() + "  " + offlineName(v.uuid()), true));
                }
                return new HiveKey(path, "Sanctions", kids, List.of(i("Sanctions", list.size())));
            });
        }
        final long id = num(path.seg(5));
        return storage.findSanction(id).thenApply(v -> {
            if (v == null) {
                return null;
            }
            final List<HiveValue> vals = new ArrayList<>(sanctionValues(v));
            vals.add(new HiveValue("Case", ValueType.LINK, "open case tree",
                    String.valueOf(v.id()), casesPath(v)));
            return new HiveKey(path, "#" + id, List.of(), vals);
        });
    }

    private CompletableFuture<HiveKey> clustersBranch(final HivePath path) {
        final ClusterIndex ci = clusters();
        if (path.depth() == 2) {
            final List<HiveChild> kids = new ArrayList<>(ci.clusterCount());
            for (final String rep : ci.reps()) {
                kids.add(new HiveChild(rep,
                        ci.members(rep).size() + " accounts · " + offlineName(rep), true));
            }
            return done(new HiveKey(path, "Clusters", kids, List.of(
                    i("Clusters", ci.clusterCount()), i("Accounts", ci.memberCount()))));
        }
        final String rep = path.seg(2);
        if (!ci.hasCluster(rep)) {
            return done(null);
        }
        final List<String> members = ci.members(rep);
        if (path.depth() == 3) {
            return done(new HiveKey(path, "cluster:" + rep, List.of(folder("Members")), List.of(
                    i("Size", members.size()),
                    v("Representative", offlineName(rep)),
                    new HiveValue("RepCase", ValueType.LINK, "open case tree", rep,
                            subjectPath(rep)))));
        }
        if (!path.seg(3).equals("Members")) {
            return done(null);
        }
        if (path.depth() == 4) {
            final List<HiveChild> kids = new ArrayList<>(members.size());
            for (final String member : members) {
                kids.add(new HiveChild(member, offlineName(member), true));
            }
            return done(new HiveKey(path, "Members", kids, List.of(i("Members", members.size()))));
        }
        final String member = path.seg(4);
        if (!members.contains(member)) {
            return done(null);
        }
        return done(new HiveKey(path, offlineName(member), List.of(), List.of(
                v("Name", offlineName(member)),
                v("UUID", member),
                i("ClusterSize", members.size()),
                new HiveValue("Case", ValueType.LINK, "open case tree", member,
                        subjectPath(member)))));
    }

    private CompletableFuture<HiveKey> suspects(final HivePath path) {
        if (path.depth() == 2) {
            final List<HiveChild> kids = new ArrayList<>();
            for (final Player p : plugin.getServer().getOnlinePlayers()) {
                kids.add(new HiveChild(p.getUniqueId().toString(), p.getName(), true));
            }
            return done(new HiveKey(path, "Suspects", kids, List.of(i("Online", kids.size()))));
        }
        final String uuidStr = path.seg(2);
        final UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (final RuntimeException e) {
            return done(null);
        }
        final Player player = plugin.getServer().getPlayer(uuid);

        if (path.depth() == 3) {
            if (player != null && player.getAddress() != null
                    && player.getAddress().getAddress() != null) {
                return fingerprint.scorePlayer(player)
                        .thenCombine(storage.coincidentAccounts(uuidStr),
                                (match, coincident) -> suspectKey(path, uuidStr, player, match,
                                        coincident.size()));
            }
            return storage.coincidentAccounts(uuidStr).thenApply(coincident ->
                    suspectKey(path, uuidStr, player, null, coincident.size()));
        }

        // Evidence branch: the per-signal bit decomposition of this suspect's
        // current probe. Routed before the Coincident guard below, which would
        // otherwise reject every non-Coincident segment.
        if (path.seg(3).equals("Evidence")) {
            return suspectEvidence(path, uuidStr, player);
        }

        if (!path.seg(3).equals("Coincident")) {
            return done(null);
        }
        return storage.coincidentAccounts(uuidStr).thenApply(list -> {
            if (path.depth() == 4) {
                final List<HiveChild> kids = new ArrayList<>(list.size());
                for (final String member : list) {
                    kids.add(new HiveChild(member, offlineName(member), true));
                }
                return new HiveKey(path, "Coincident", kids, List.of(i("Coincident", list.size())));
            }
            final String member = path.seg(4);
            if (!list.contains(member)) {
                return null;
            }
            return new HiveKey(path, offlineName(member), List.of(), List.of(
                    v("Name", offlineName(member)),
                    v("UUID", member),
                    new HiveValue("Case", ValueType.LINK, "open case tree", member,
                            subjectPath(member))));
        });
    }

    private HiveKey suspectKey(final HivePath path, final String uuidStr, final Player player,
                               final EvasionMatch match, final int coincidentCount) {
        final List<HiveValue> vals = new ArrayList<>(6);
        vals.add(v("Name", player != null ? player.getName() : offlineName(uuidStr)));
        vals.add(v("UUID", uuidStr));
        vals.add(v("Online", player != null ? "true" : "false"));
        vals.add(i("Coincident", coincidentCount));
        if (match != null) {
            vals.add(new HiveValue("EvasionScore", ValueType.SCORE,
                    String.format(java.util.Locale.ROOT, "%.2f", match.score()),
                    String.format(java.util.Locale.ROOT, "%.4f", match.score()), null));
            vals.add(new HiveValue("MatchedBan", ValueType.LINK, "#matches " + match.matchedSignals(),
                    match.bannedUuid(), subjectPath(match.bannedUuid())));
        }
        return new HiveKey(path, player != null ? player.getName() : uuidStr,
                List.of(folder("Coincident"), folder("Evidence")), vals);
    }

    private CompletableFuture<HiveKey> suspectEvidence(final HivePath path, final String uuidStr,
                                                       final org.bukkit.entity.Player player) {
        // Evidence requires a live address to form a probe; an offline suspect
        // yields a status row rather than an empty or absent node.
        if (player == null || player.getAddress() == null
                || player.getAddress().getAddress() == null) {
            return done(new HiveKey(path, "Evidence", List.of(),
                    List.of(enun("Status", "player offline or address unknown"))));
        }

        // A diagnostic probe: no reverse-DNS, since this executes on the request
        // pool and the display value is the same either way for a live address.
        final org.synergyst.minutiae.fingerprint.ProbeArrays probe =
                org.synergyst.minutiae.fingerprint.SignalCollector.probe(
                        player.getAddress().getAddress(), player.getName(),
                        fingerprint.engine().networkClassifier(), false);
        final long now = System.currentTimeMillis();

        return storage.matchingSignals(probe.types(), probe.values(), now).thenApply(rows -> {
            // Restrict the agreement set to this suspect's own stored signals, so
            // the decomposition explains the self-match rather than a cross-match.
            final List<org.synergyst.minutiae.fingerprint.MatchingSignalRow> mine =
                    rows.stream().filter(r -> r.uuid().equals(uuidStr)).toList();
            if (mine.isEmpty()) {
                return new HiveKey(path, "Evidence", List.of(),
                        List.of(enun("Status", "no stored signal agreement")));
            }

            final org.synergyst.minutiae.fingerprint.MatchEvidence ev =
                    fingerprint.engine().assessAccount(mine, now);

            final List<HiveValue> vals = new ArrayList<>(ev.contributions().size() + 3);
            // Header: the posterior probability and the log-odds it decomposes to.
            vals.add(new HiveValue("Probability", ValueType.SCORE,
                    String.format(java.util.Locale.ROOT, "%.4f", ev.probability()),
                    String.format(java.util.Locale.ROOT, "%.4f", ev.probability()), null));
            vals.add(v("TotalBits", String.format(java.util.Locale.ROOT, "%.3f", ev.totalBits())));
            vals.add(v("PriorBits", String.format(java.util.Locale.ROOT, "%.3f", ev.priorBits())));

            // One row per contributing signal: its decayed, capped bit weight, the
            // value-conditioned non-match likelihood u, the decay factor, and the
            // agreed value. A moderator reads directly how many bits each supplied.
            for (final org.synergyst.minutiae.fingerprint.EvidenceContribution c
                    : ev.contributions()) {
                final String label = org.synergyst.minutiae.fingerprint.SignalType
                        .fromCode(c.type()).configKey();
                vals.add(v(label, String.format(java.util.Locale.ROOT,
                        "%+.3f bits  (u=%.4f, decay=%.2f)  %s",
                        c.bits(), c.uFrequency(), c.decay(), c.value())));
            }
            return new HiveKey(path, "Evidence", List.of(), vals);
        });
    }

    // ----------------------------------------------------------------------
    // Cluster cache
    // ----------------------------------------------------------------------

    private ClusterIndex clusters() {
        final long now = System.currentTimeMillis();
        final ClusterIndex cached = clusterCache;
        if (cached != null && now - cached.builtAt() < CLUSTER_TTL_MS) {
            return cached;
        }
        synchronized (clusterLock) {
            final ClusterIndex again = clusterCache;
            if (again != null && System.currentTimeMillis() - again.builtAt() < CLUSTER_TTL_MS) {
                return again;
            }
            final List<SignalLinkRow> links = storage.listSignalLinks(CLUSTER_LINK_CAP).join();
            // The cluster resolution requires the edge-classification config and
            // the corpus size that scales the soft-link IDF weight; both are read
            // from the engine without any storage round trip.
            final ClusterIndex built = ClusterIndex.build(
                    links,
                    fingerprint.engine().clusterConfig(),
                    fingerprint.engine().oracle().totalAccounts(),
                    System.currentTimeMillis());
            clusterCache = built;
            return built;
        }
    }

    // ----------------------------------------------------------------------
    // Fingerprint helpers
    // ----------------------------------------------------------------------

    private static org.synergyst.minutiae.fingerprint.SignalType typeByKey(final String key) {
        for (final org.synergyst.minutiae.fingerprint.SignalType t
                : org.synergyst.minutiae.fingerprint.SignalType.values()) {
            if (t.configKey().equals(key)) {
                return t;
            }
        }
        return null;
    }

    private static String encSeg(final String s) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String decSeg(final String s) {
        try {
            return new String(java.util.Base64.getUrlDecoder().decode(s),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (final RuntimeException e) {
            return "";
        }
    }

    private static String casesPath(final SanctionView v) {
        return "/HKEY_CASES/Players/" + v.uuid() + "/Sanctions/" + v.measure()
                + "/" + v.staff() + "/" + v.id();
    }

    private static String subjectPath(final String uuid) {
        return "/HKEY_CASES/Players/" + uuid;
    }

    // ----------------------------------------------------------------------
    // Value rendering
    // ----------------------------------------------------------------------

    private List<HiveValue> sanctionValues(final SanctionView v) {
        final List<HiveValue> out = new ArrayList<>(14);
        out.add(i("Id", v.id()));
        out.add(v("Measure", v.measure()));
        out.add(v("Rule", v.rule() != null ? v.rule() : "-"));
        out.add(v("Reason", v.reason() != null ? v.reason() : "-"));
        out.add(enun("Status", status(v)));
        out.add(ts("Issued", v.issuedAt()));
        out.add(v.expiresAt() == 0L ? v("Expires", "never") : ts("Expires", v.expiresAt()));
        out.add(mask("Behaviour", v.behaviourMask()));
        out.add(v("Appealable", v.appealable() == 1 ? "true" : "false"));
        out.add(v("Origin", v.layout() != null ? "::" + v.layout() : "manual"));
        out.add(v("Staff", v.staff()));
        if (v.parentId() != 0L) {
            out.add(v("Parent", "#" + v.parentId()));
        }
        if (v.liftedAt() != 0L) {
            out.add(ts("LiftedAt", v.liftedAt()));
            out.add(v("LiftedBy", safe(v.liftedBy())));
        }
        if (v.liftedAt() != 0L) {
            out.add(enun("LiftKind",
                    org.synergyst.minutiae.execute.LiftKind.fromCode(v.liftKind()).name()));
        }
        return out;
    }

    private HiveKey appealKey(final HivePath path, final AppealView a) {
        return new HiveKey(path, "#" + a.id(), List.of(), List.of(
                i("Id", a.id()),
                v("Sanction", "#" + a.banId()),
                v("Appellant", a.appellant()),
                v("Text", a.text()),
                enun("Status", a.status()),
                v("Verdict", safe(a.verdict())),
                v("Reviewer", safe(a.reviewer())),
                ts("Created", a.createdAt())));
    }

    private HiveKey auditKey(final HivePath path, final AuditRow r) {
        final List<HiveValue> vals = new ArrayList<>(5);
        vals.add(v("Action", r.action()));
        vals.add(v("Actor", r.actor()));
        vals.add(ts("Time", r.ts()));
        vals.add(v("Detail", safe(r.detail())));
        if (r.banId() != null) {
            vals.add(v("Sanction", "#" + r.banId()));
        }
        return new HiveKey(path, String.valueOf(r.id()), List.of(), vals);
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------


    private static String status(final SanctionView v) {
        if (v.liftedAt() != 0L) {
            return "lifted";
        }
        if (v.active() == 1) {
            return "active";
        }
        if (v.stayed() == 1) {
            return "stayed";
        }
        return "inactive";
    }

    private String offlineName(final String uuid) {
        try {
            return names.name(UUID.fromString(uuid));
        } catch (final RuntimeException e) {
            return uuid;
        }
    }

    private static String safe(final String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    private static long num(final String s) {
        try {
            return Long.parseLong(s);
        } catch (final NumberFormatException e) {
            return -1L;
        }
    }

    private static HiveChild folder(final String name) {
        return new HiveChild(name, null, true);
    }

    private static HiveValue v(final String n, final String value) {
        return new HiveValue(n, ValueType.STRING, value == null ? "-" : value, value, null);
    }

    private static HiveValue i(final String n, final long value) {
        return new HiveValue(n, ValueType.INT, Long.toString(value), Long.toString(value), null);
    }

    private static HiveValue enun(final String n, final String value) {
        return new HiveValue(n, ValueType.ENUM, value, value, null);
    }

    private static HiveValue ts(final String n, final long value) {
        final String abs = WHEN.format(Instant.ofEpochMilli(value));
        final String rel = HiveFmt.relative(value, System.currentTimeMillis());
        return new HiveValue(n, ValueType.TIMESTAMP, abs + "  (" + rel + ")", Long.toString(value), null);
    }

    private static HiveValue mask(final String n, final long m) {
        final StringBuilder sb = new StringBuilder();
        for (final Behaviour b : Behaviour.values()) {
            if (b.in(m)) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(b.name());
            }
        }
        return new HiveValue(n, ValueType.MASK, sb.length() == 0 ? "-" : sb.toString(),
                Long.toString(m), null);
    }

    private static CompletableFuture<HiveKey> done(final HiveKey k) {
        return CompletableFuture.completedFuture(k);
    }
}