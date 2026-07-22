# Minutiae Enforcement

Rule-bound sanction orchestration engine for Paper 1.21.4+.

Moderation kernel built around one premise: every sanction is a legal act. A sanction cites a rule, carries precedent, may be escalated, stayed, suspended, appealed, amended, joined into a case, vacated, pardoned, or served out. The plugin models these distinctions explicitly instead of collapsing them into "banned: yes/no".

Requires Java 21 and Paper 1.21.4 or newer. No external dependencies: storage is embedded SQLite, the web panel runs on the JDK's own HTTP server, and cross-server propagation uses the shared database rather than a broker.

---

## Table of contents

1. [Architecture overview](#architecture-overview)
2. [Installation and boot](#installation-and-boot)
3. [Core concepts](#core-concepts)
    - [Measures](#measures)
    - [Rules](#rules)
    - [Layouts](#layouts)
    - [Annotations](#annotations)
    - [Durations](#durations)
4. [The `/enforce` command grammar](#the-enforce-command-grammar)
5. [Precedent, escalation, and procedural windows](#precedent-escalation-and-procedural-windows)
6. [Lifting sanctions](#lifting-sanctions)
7. [Appeals](#appeals)
8. [Behavioural enforcement](#behavioural-enforcement)
9. [Chat transcripts](#chat-transcripts)
10. [Fingerprinting and ban-evasion detection](#fingerprinting-and-ban-evasion-detection)
11. [Automatic enforcement (ALAM)](#automatic-enforcement-alam)
12. [Multi-server deployment](#multi-server-deployment)
13. [Web panel (Hive)](#web-panel-hive)
14. [Localisation](#localisation)
15. [Storage](#storage)
16. [Permissions](#permissions)
17. [Configuration reference](#configuration-reference)
18. [Command reference](#command-reference)
19. [Operational notes and failure posture](#operational-notes-and-failure-posture)
20. [Building](#building)

---

## Architecture overview

The plugin boots as an ordered sequence of stages, each publishing services into an explicit container. There is no reflection, no classpath scanning, no annotation-driven injection; every dependency edge is a constructor argument, and boot order is the dependency order. A stage failure aborts the boot, unwinds already-booted components in reverse order, and disables the plugin rather than running half-initialised.

```
config -> async -> messages -> storage -> rules -> annotations -> lang
       -> layouts -> fingerprint -> behaviour -> maintenance -> notify
       -> network -> enforce -> dispatch -> web -> commands -> ready
```

Threading discipline, stated once and enforced everywhere:

- The server main thread never performs blocking I/O. All storage access is dispatched to a virtual-thread-per-task executor; physical concurrency is bounded by the SQLite connection pool, not by thread count.
- Player-facing and entity-touching work is always marshalled back to the main thread.
- The asynchronous pre-login thread is permitted to join storage futures directly (bounded by the pool acquisition timeout), since the event is inherently asynchronous and a timeout there fails open.
- Hot-path listeners (movement, chat) read only in-memory concurrent state behind an emptiness guard: on a server with no active behavioural sanctions, each event costs one map-emptiness check.

Diagnostics use a kernel-ring-buffer style logger: every line carries a microsecond-resolution elapsed timestamp and a subsystem tag, so boot stages and steady-state activity are correlatable from the console alone.

## Installation and boot

1. Drop the jar into `plugins/`.
2. Start the server once. Defaults are provisioned: `config.yml`, `rules.yml`, `layouts.yml`, `lang/en.yml` (and the configured default locale's bundle), an empty `alam/` directory, `minutiae.db`, and `server-id`.
3. Edit `rules.yml` to reflect your server's actual rules. Rule identifiers use the grammar `P.<digits>[.<digits>...]` (for example `P.3.2`). A malformed identifier or empty description is fatal at boot by design: layout referential integrity depends on a complete registry.
4. Edit `layouts.yml` to define your sanction presets.
5. If you enable the web panel, change both tokens in `config.yml` first.

Boot output enumerates each stage with counts (rules loaded, layouts accepted/disabled, annotations, armed automata, and so on). Read it once after any configuration change; every rejected definition is reported with a reason, and rejections are non-fatal except in the rule registry.

## Core concepts

### Measures

The closed set of enforcement mechanisms, ordered by severity. The severity order is load-bearing: it classifies a `@commute` as a mitigation or an aggravation, each gated by its own permission node.

| Measure | Severity | Temporal class | Effect |
|---|---|---|---|
| `WARN` | 0 | instantaneous | Message to the target; recorded, counts toward the warn-first quota |
| `CENSURE` | 1 | instantaneous | Formal recorded reprimand, delivered as a message |
| `MUTE` | 2 | temporal | Chat and configured commands suppressed |
| `KICK` | 3 | instantaneous | Removal from the server |
| `QUARANTINE` | 4 | temporal | Confinement within a radius of a configured anchor |
| `SUSPENSION` | 5 | temporal | Connection refused for the duration |
| `CUSTODY` | 6 | permanent | Connection refused permanently |

The temporal class is a validated invariant, not a convention. An instantaneous measure must not carry a duration; a temporal measure must; the permanent measure fixes it. Violations are rejected at layout load, at command resolution, and in ALAM verification, each with its own diagnostic.

### Rules

`rules.yml` maps identifiers to authoritative descriptions:

```yaml
rules:
  "P.3.2": "No destruction of structures owned by other players"
  "P.5.1": "No use of unauthorised third-party client modifications"
```

The identifier grammar is deliberately restricted to `P.` plus dot-separated digit runs: pure ASCII, no section sign (which doubles as the client formatting-code introducer and is unsafe to carry through chat and serialisation). The registry is mirrored into the database (`rules_cache`) with content-hash reconciliation, so historical sanctions retain the rule text as it stood.

Precedent is keyed by rule. A sanction citing no rule accrues no precedent and cannot escalate.

### Layouts

Layouts are named sanction presets invoked as `::key`. They support single inheritance with additive annotations:

```yaml
layouts:
  _base_cheat:                      # underscore prefix = private, not invocable
    rule: "P.5.1"
    annotations:
      - "@evidence(required)"
      - "@ip-lock"

  cheating:
    extends: _base_cheat
    measure: CUSTODY
    reason: "Use of unauthorised client modifications"

  griefing:
    rule: "P.3.2"
    measure: SUSPENSION
    reason: "Griefing of player-owned structures"
    duration: 7d
    escalation: [ "7d", "30d", "permanent" ]
    annotations:
      - "@evidence(required)"
      - "@escalate"
      - "@notify(staff)"
```

Merge semantics under inheritance: scalar fields (rule, reason, measure, duration) take the child value when present, the parent's otherwise; the escalation ladder is replaced wholesale; annotations are concatenated parent-first. Inheritance cycles, missing parents, and descendants of failed ancestors are each rejected in isolation with a diagnostic; unrelated layouts load normally. An invocable layout must resolve to a rule that exists, a measure, and a duration consistent with the measure's temporal class, or it is disabled.

Layouts may also be *stamped* programmatically by ALAM definitions (see below). File-authored keys win on collision.

### Annotations

Annotations are the modifier vocabulary of the command surface, written `@name` or `@name(params)`, negatable inline as `!@name` (which removes a layout-supplied annotation; negation is meaningless in configuration and rejected there). The catalogue is code-defined and capped at 64 entries, so any annotation set is a single `long` bitmask; relationship checks (implication closure, conflicts, requirements) are constant-time bit operations over precomputed matrices.

**Behavioural annotations** (valid in layouts and inline):

| Annotation | Parameters | Effect |
|---|---|---|
| `@shadow` | none | Covert isolation: hidden from others, interactions voided, chat echoed only to the sender. The sanction does not reveal itself. Implies `@silent` by default matrix. |
| `@ghost` | none | Hidden from others and interaction-voided; a staff observation tool |
| `@rubberband` | none | Movement beyond the configured leash is reverted |
| `@silent` | none | Suppresses the public broadcast |
| `@ip-lock` | none | Marks the sanction for address-bound enforcement |
| `@evidence` | empty, `required`, or `url=/type=` | Evidence attachment/requirement |
| `@notify(ch, ...)` | one or more channel names | Routes a notification to configured channels |
| `@warn-first(n)` | positive integer | The measure applies only after `n` prior formal warnings; earlier offences downgrade to `WARN` |
| `@escalate` | none | Engages the layout's escalation ladder against precedent |
| `@appeal(on\|off)` | toggle | Controls appealability (default on) |
| `@decay(d)` | duration | The sanction stops counting toward precedent after the window |
| `@tariff(d)` | duration | Minimum-duration floor applied after escalation; temporal measures only |
| `@probation(d)` | duration | Opens a probation window; a repeat offence within it is aggravated |
| `@suspended(d)` | duration | Suspended sentence: recorded but inactive until recidivism under the same rule within the window activates it |
| `@transcript` | none | Persists a snapshot of the subject's recent chat with the sanction |
| `@expunge(d)` | duration | Schedules removal from docket visibility |
| `@review(d)` | duration | Schedules a mandatory staff review, delivered via notification |

**Inline-only directives** (rejected in configuration):

| Directive | Parameters | Effect |
|---|---|---|
| `@measure(M)` | measure name | Selects a measure for a manual (layout-less) sanction |
| `@commute(M)` | measure name | Replaces a layout's measure; direction-gated by permission |
| `@count(P.X)` | rule id | Cites an additional rule (multi-count sanction) |
| `@remand` | none | Standalone pre-decision holding action (quarantine); combines with nothing |
| `@amend(id)` | sanction id | Mutates an existing sanction instead of issuing; exclusive with layout/measure/commute/remand |
| `@weave(#id)` | sanction id | Joins the new sanction under an existing case (joinder); precedent is then chain-scoped |
| `@now` | none | Bypasses the warn-first gate |
| `@dry-run` | none | Resolves and reports without persisting or applying |
| `@as(name)` | staff name | Attribution override |
| `@backdate(d)` | duration | Backdates the issue timestamp; requires `@evidence` by default matrix |
| `@stay(d)` | duration | Defers activation; the stay activates on the target's next sanction within the window |
| `@link(ref)` | reference | Attaches an external reference (URL, ticket) |
| `@reason(text)` | text | Reason as an annotation (equivalent to `reason=` override) |
| `@note(text)` | text | Internal case note, never shown to the subject |
| `@waive(name)` | annotation name | Waives a companion requirement (e.g. issue `@backdate` without `@evidence`) |

Every annotation is gated by `minutiae.annotation.<name>`. Relationships between annotations (`implies`, `requires`, `conflicts`) are operator-configurable in `config.yml` under `annotations:` and compiled into the matrix at boot; the shipped defaults are `shadow -> silent` and `backdate requires evidence`.

### Durations

Compound unit-suffixed expressions: `30m`, `1h`, `1d12h`, `7d`, `permanent` (also `perm`, `forever`). Units: `s m h d w`. Parsing is strict; `3hx` and a bare number are errors with specific diagnostics.

## The `/enforce` command grammar

A sanction specification is a whitespace-delimited token stream in which token classes are distinguished by shape, so order is free:

```
/enforce <target> [::layout] [@annotation...] [!@annotation...] [key=value...]
```

Token classification:

- `::key` — layout selector (at most one)
- `@name(...)` / `!@name` — annotation or negation
- `key=value` — scalar override; recognised keys are `duration` and `reason`; values may be quoted: `reason="grief at spawn, repeated"`
- anything else — the target (exactly one required)

The tokeniser respects double quotes and parenthesis nesting, so `@notify(staff-chat, discord)` survives as one token and quoted values may contain spaces, commas, and equals signs.

Examples:

```
/enforce Notch ::griefing
/enforce Notch ::griefing duration=14d reason="second offence, evidence in ticket"
/enforce Notch ::cheating @shadow
/enforce Notch @measure(MUTE) duration=3h @transcript
/enforce Notch ::toxic !@warn-first @now
/enforce Notch ::griefing @count(P.4.1) @weave(#118)
/enforce Notch @amend(42) duration=30d reason="corrected after review"
/enforce Notch @remand
/enforce Notch ::griefing @dry-run
```

Resolution proceeds through a fixed pipeline: layout binding, directive/annotation partitioning, measure resolution (with directional commute gating), behavioural merge (layout annotations plus inline additions minus negations), per-annotation permission checks, relationship validation over the implication-expanded mask, duration resolution against the temporal invariant, then reason derivation (override > `@reason` > layout reason > rule description). Every failure is a keyed, localised diagnostic naming the exact violation. The resolver is pure: nothing is persisted until it succeeds.

Tab completion is context-sensitive and reconstructs the partial command state per keystroke: it offers layouts only while no measure source exists, duration exemplars only while a temporal measure lacks one, negation candidates only from the bound layout's own annotations, and recent sanction identifiers from an in-memory ring warmed at boot. Completion never touches storage.

`@dry-run` is the rehearsal path: the full resolution and escalation computation runs and is reported, and nothing is written.

## Precedent, escalation, and procedural windows

**Precedent** is the count of prior non-warning sanctions by the same subject under the same rule, excluding decayed sanctions (`@decay`) and excluding *vacated* lifts (pardoned and time-served lifts still count; see lift kinds). When the sanction is woven into a case via `@weave`, precedent is computed over the whole joinder chain instead.

**Warn-first gate**: with `@warn-first(n)`, an offence with fewer than `n` prior warnings is downgraded to a formal `WARN`, which itself accrues toward the quota. `@now` bypasses the gate.

**Escalation**: with `@escalate` and a layout ladder, the rung is selected by precedent count (clamped to the ladder). An active probation window aggravates the outcome per the configured policy: `STEP` mode advances one extra rung; `MULTIPLIER` mode scales the selected rung's duration. An explicit `duration=` override disables escalation for that issuance. `@tariff` then applies as a floor.

**Stays** (`@stay(d)`): the sanction is recorded inactive; any subsequent sanction against the subject within the window activates it.

**Suspended sentences** (`@suspended(d)`): recorded inactive; a further sanction *under the same rule* within the window activates it. Mutually exclusive with `@stay` (the two deferral conditions are incoherent together).

**Decision trace**: every issuance persists a trace row recording the precedent counts consulted, probation state, ladder index, base and final durations, and any warn downgrade. The web panel renders it under the sanction's `Rationale` node, so a duration is explainable after the fact without recomputation.

## Lifting sanctions

A lift is not one operation. The **kind** records the legal character of the removal and governs precedent:

| Kind | Command literal | Precedent | Meaning |
|---|---|---|---|
| Vacate | `lift <id> vacate` (default) | excluded | Wrongly issued; expunged from precedent |
| Pardon | `lift <id> pardon` | retained | Rightly issued, remitted as clemency |
| Time served | `lift <id> served` | retained | Terminated early as served; an early expiry |

Kind selection is permission-gated (`minutiae.lift.pardon`, `minutiae.lift.served`); vacate rides the base lift permission. Two further gates depend on the target sanction and apply on every entry path, including appeal acceptance and the web panel: lifting another staff member's sanction requires `minutiae.lift.foreign`, and lifting a connection-blocking sanction requires `minutiae.lift.blocking`.

The lift remainder accepts its own closed annotation subset: `@silent`, `@dry-run`, `@note(text)`, `@notify(...)`, `@probation(d)` (rejected on a vacate, whose record leaves precedent), and the lift-only `@cascade`, which extends the operation over the whole joinder chain under `minutiae.lift.cascade`.

```
/enforce lift 42
/enforce lift 42 pardon @probation(30d) "first offence, six months clean"
/enforce lift 42 vacate @cascade @dry-run
/enforce liftall rule P.3.2            # preview
/enforce liftall rule P.3.2 confirm "rule retired"
/enforce unlift 42 "lift was clerical error"
```

Bulk lifts (`liftall`, by rule or staff, always vacate) require an explicit preview-then-`confirm` sequence. `unlift` reverses an erroneous lift, recomputing the active flag from temporal state and re-applying effects to an online subject.

Lifting recomputes the online subject's behavioural state from their *remaining* active sanctions, so lifting one of several overlapping sanctions removes only that sanction's contribution.

## Appeals

Players contest sanctions with `/appeal <id> <text>` (`minutiae.appeal.submit`, default true). Submission requires the sanction to be appealable, owned by the appellant, unlifted, and without a pending duplicate. Staff review with:

```
/enforce appeals [page]
/enforce appeal <id> accept [reason]
/enforce appeal <id> deny [reason]
```

An accepted appeal lifts the sanction under the **vacate** kind: acceptance asserts the sanction was wrongly issued or excessive, so the record leaves precedent. Submissions and decisions route through the notification service.

## Behavioural enforcement

Non-connection constraints are tracked per player as a bitmask with per-constraint expiries, held in a concurrent in-memory store and rebuilt from storage on join:

- **MUTED** — chat cancelled with a notice; configured command roots blocked
- **QUARANTINED** — confined within the configured radius of the anchor; boundary pulls back with a rate-limited notice
- **GHOSTED** — hidden from all other players, world interactions voided
- **RUBBERBAND** — per-move displacement beyond the leash reverted (squared-distance comparison, no allocation)
- **SHADOWED** — the covert composite: hidden, interaction-voided, chat silently reduced to the sender's own view. A shadowed connection-blocking sanction *admits* the player at the door and isolates them on join, so the sanction is never revealed by a refusal screen.

Overlapping sanctions merge by taking the later expiry per constraint. Expiry is lazy on read; a periodic maintenance sweep reclaims fully-expired records and reconciles concealment for online players, so a lapsed ghost becomes visible without rejoining. Concealment is reconciled in both directions on join, so a ghost present before a newcomer joins remains hidden from them.

## Chat transcripts

A bounded ring of recent chat lines is retained per player (capacity, line length, tracked-player count, and post-quit retention all configurable, all bounded). Lines are captured at monitor priority on the async chat thread, serialised to plain text, stripped of control and formatting characters, and truncated — nothing captured is ever re-interpreted as markup or format string.

A snapshot is persisted with a sanction when `@transcript` is present (mode `ANNOTATION`), or additionally for any connection-blocking or behavioural sanction (mode `ALWAYS`). Persisted transcripts render as RFC 4180 CSV in the web panel.

## Fingerprinting and ban-evasion detection

The evasion detector is a calibrated Bayesian record-linkage system (Fellegi–Sunter), not a weight-sum heuristic — although a legacy linear scorer with configurable weights remains for signal capture.

**Signals.** Each session accumulates: full address, /24 (or /64) subnet, provider/ASN label (from an operator-configured CIDR catalogue), optional reverse-DNS host pattern, locale, client brand, protocol version, view distance, skin-part bitmask, main hand, and a digit-normalised name pattern (`Steve123` -> `Steve###`). Signals are persisted with every connection-blocking sanction.

**Scoring.** On login, the probe is matched against stored signals of active suspensions and custodies. Each agreement contributes a log-likelihood-ratio weight in bits: `log2(m/u)`, where `m` is the signal type's learned reliability (a Beta belief, consumed through a conservative lower-confidence bound so poorly-established types are attenuated) and `u` is the value's coincidental-agreement probability, estimated per value as its distinct-account frequency over the corpus — so a shared VPN address or ubiquitous locale automatically contributes little or negative evidence. Weights decay exponentially with observation age per type's half-life, are capped per signal, and are gated for structural dependence: within one family (address: full > subnet > rDNS > ASN; client: brand > protocol) only the most specific agreement counts, so a full-address match is not double-counted through its own subnet. The posterior probability is `1 / (1 + 2^-S)`; crossing the flag threshold raises a staff alert. **No automatic sanction is applied on a flag.**

**Behavioural correlation.** The best candidate is adjusted by a bounded log-odds term from temporal activity: same-address logout-to-login handoffs raise the score, simultaneous online presence lowers it (two people, not one), and active-hour histogram similarity weakly raises it. The adjustment is clamped and cannot override the field evidence.

**Learning.** Moderator adjudication of a flag reinforces or penalises the participating signal types' Beta beliefs, which persist and rebuild the model.

**Clustering.** The web panel's `HKEY_FINGERPRINTS/Clusters` branch resolves account clusters by a two-phase procedure: rare shared values of hard-eligible types union accounts unconditionally; common values accumulate IDF-weighted soft evidence between super-nodes and merge only above a bit threshold; ubiquitous values are discarded outright, preventing a public proxy from fusing the server into one mega-cluster.

Diagnostic inspection: `/me-ban fingerprint <player>` shows the session's signals and the current best match; the panel's `Suspects/<player>/Evidence` node shows the full per-signal bit decomposition.

**Proxy caveat:** behind a proxy without IP forwarding, every connection presents the proxy's address and the entire address family silently degrades. A sentinel detects the symptomatic pattern (many distinct accounts, one source address) and reports it prominently at boot-time traffic; it cannot fix the configuration, only surface it.

## Automatic enforcement (ALAM)

ALAM is a statically typed, load-time-compiled definition language for enforcement automata, loaded from `plugins/MinutiaeEnforcement/alam/*.alam`. Full language documentation ships separately; this section covers only its integration contract.

A source compiles through lexing, parsing, elaboration (types, effects, exhaustiveness), compile-time evaluation, verification, and lowering. A unit either yields a complete verified plan or is rejected whole with positioned diagnostics carrying stable codes; a rejected unit arms nothing while siblings load normally. Units may also *stamp* layout definitions, ingested by the layout registry alongside file-authored ones (file keys win on collision, deterministic by file-name order across units).

At runtime, armed rules bind to events (`chat`, `break`, `login`, `evasion`) through three trigger forms: atomic (every occurrence), repeated (a per-partition sliding recurrence window), and sequence (an ordered per-partition partial match with per-step guards, whose rule guard and verdict observe *every* participating event's facts). A firing verdict materialises a sanction descriptor onto the command surface and drives it through the **identical resolver and executor as a manual command**, under a configured system actor whose explicit permission set bounds what automation may ever do.

Safety posture, layered:

- An automaton not in the armed set (and not under global arming) evaluates in **forced dry-run**: it resolves and reports but applies nothing. This is the default.
- A per-automaton per-minute throttle bounds firing rate; saturation **self-mutes** the automaton until restart, bounding the blast radius of a misbehaving definition.
- Guard evaluation faults are absorbed as unsatisfied guards; verdict faults as non-firings with a logged warning. No throwable propagates into a platform event handler.
- Guard environment reads (precedent, fingerprint score, online status) are served from a TTL cache with off-thread fills; a miss returns a neutral value that fails its comparison. No I/O ever occurs on the event thread.

Every non-simulated firing appends an audit entry. Inspection and rehearsal:

```
/alam list
/alam simulate chat Notch "some message text"
/alam simulate evasion Notch 0.95
```

Simulation evaluates every candidate in forced dry-run without consuming window, sequence, throttle, or mute state.

## Multi-server deployment

Networking is disabled by default; a standalone server carries zero networking overhead (all publish calls are no-ops through the inert bus).

For a proxied network: point every instance at one shared database and set `network.enabled: true`. Each instance carries a stable identifier (configured, or generated and persisted to the `server-id` file) used for sanction attribution, directive addressing, and cursor tracking.

Cross-server effects travel as **directives**: durable rows in the shared database, published by the originating instance and consumed by every other instance through periodic polling against a persistent per-instance cursor. Two kinds exist, both idempotent by contract: `KICK` (remove an online subject with a localised screen) and `SYNC_BEHAVIOUR` (rebuild the subject's behavioural state from the authoritative tables and reconcile concealment). Delivery is at-least-once with per-server ordering; a crash between application and cursor persistence replays at most one batch, which idempotency absorbs. Directives expire after a TTL and are purged; an instance offline longer than the TTL misses them *by design*, because the join-time reconciliation path (behaviour reload on join, access gate at pre-login) re-derives the same state from the authoritative tables. No message broker, no plugin channels.

Deliberately instance-local: complex-event state (recurrence windows, sequence partials), automaton throttles, and self-mute sets. Platform events are local by nature; cross-instance event ordering is a guarantee the platform cannot provide for a semantics no moderation scenario demands.

Proxy requirements: Velocity modern forwarding or BungeeCord `ip_forward` with the matching Paper configuration. Without forwarding, fingerprint address signals collapse onto the proxy address and identity attribution degrades silently; the forwarding sentinel reports the condition but cannot repair it.

## Web panel (Hive)

The panel is a registry-style navigator over a read-only projection of the authoritative store, served by the JDK's embedded HTTP server. It is disabled by default and binds to loopback; both are deliberate. Expose it beyond loopback only behind a TLS-terminating reverse proxy.

The namespace is a virtual filesystem of *hives*, each a mounted subtree resolved by path descent:

| Hive | Content |
|---|---|
| `HKEY_CASES` | The sanction case tree: subject -> measure -> enforcer -> sanction, each sanction carrying its provisions, captured signals, transcript, decision rationale, amendments, woven children, and audit trail. Provenance is expressed by hierarchy, not cross-reference: to follow who sanctioned whom, you descend. |
| `HKEY_APPEALS` | Appeals by appellant and in aggregate |
| `HKEY_AMENDS` | Amendment audit entries and per-sanction traceback histories |
| `HKEY_FINGERPRINTS` | The forensic network: signal values by type, resolved account clusters, and online suspects with live evidence decomposition |
| `HKEY_SYSTEM` | Schema version, uptime, aggregate counters, and the global audit trail |

Access is gated by opaque tokens mapped to a role: `read` navigates, `write` additionally performs actions. Token comparison is constant-time over the whole token set, denying a timing side channel. The session cookie is `HttpOnly; SameSite=Strict`, with `Secure` added automatically on any non-loopback bind.

Write actions (lift, appeal accept/deny, and free-form sanction issuance through the `Manage…` control) dispatch through a synthetic sender whose permission predicate admits only a fixed scoped grant set. The set deliberately withholds moderator impersonation (`@as`), aggravating commutation, and amendment: a compromised write token is bounded to less than what a moderator holding the staff bundle can do, and every panel action traverses the identical resolver, executor, and audit as a manual command.

```yaml
web:
  enabled: true
  bind: 127.0.0.1
  port: 8787
  tokens:
    - { token: "change-me-read",  role: read }
    - { token: "change-me-write", role: write }
```

## Localisation

Every user-facing string renders from per-locale bundles under `lang/<tag>.yml` (English, Russian, and Ukrainian ship embedded). Templates use legacy ampersand colours extended with `&#rrggbb` hex; placeholders are `{name}`. Scalar placeholder values are sanitised of colour codes, so a player name or reason can never inject formatting.

Bundle selection is per-recipient with a per-key fallback chain: exact locale tag, then language portion, then the configured default locale, then the *jar-embedded* bundle of the default locale, then embedded English. The embedded layer exists because on-disk bundles are provisioned once and never overwritten: keys introduced by a plugin update render from the shipped bundle until the operator merges them, and the load reports how many keys each on-disk bundle lacks.

Several rendering concerns are handled structurally rather than by string concatenation:

- **Rich placeholders.** The `{reason}` token renders as an abbreviated inline fragment with a hover card carrying the full reason, measure, rule, duration, staff, and timestamp. `{measure}`, `{duration}`, and `{kind}` render per recipient locale at delivery, so one broadcast shows each viewer their own localisation.
- **Grammatical rank forms.** Rank display names live under the dynamic `rank.<id>` namespace; inflected locales define case forms as `rank.<id>.<form>` (the shipped convention is `by` for the agentive context, genitive in Russian and Ukrainian), with fallback to the base entry. English defines only the base entry and every form collapses onto it.
- **Localised durations.** Duration rendering decomposes into unit-suffixed segments whose suffixes are bundle entries, cached per locale.

The pre-login refusal screen renders in the default locale, since no player locale exists before login completes.

## Storage

Embedded SQLite through a fixed-capacity connection pool: all connections opened eagerly, WAL journal mode, `synchronous=NORMAL`, foreign keys on, per-connection busy timeout, and a bounded acquisition timeout that fails deterministically instead of blocking forever. The most recently released connection is reused first.

Schema changes are forward-only migrations applied transactionally at boot; a failing migration rolls back and aborts without advancing the version marker, leaving the database in its last consistent state. The catalogue is append-only: released migrations are never edited, and corrections are expressed as new higher-versioned entries.

Principal tables: `bans` (one row per sanction, carrying the full procedural state: measure, expiry, stay, suspension, decay, probation, review, expungement, lift metadata with kind, joinder parent, behavioural mask, issuing server), `provisions` (multi-count citations), `signals` and the frequency aggregates, `chat_transcript`, `sanction_trace` (decision rationale), `appeals`, `audit`, `case_notes`, `usernames` (name history), `session_interval` and `activity_hist` (correlation inputs), `signal_belief` (learned reliabilities), `directives` and `directive_cursor` (networking), `preferences`, and `rules_cache`.

Ordinal-persisted enumerations (`Behaviour`, `SignalType`, `LiftKind`, `TokenKind`) must never be reordered once released, only appended. This constraint is documented on each enum and is the price of compact integer persistence.

`/me-ban ping` probes the backend and reports round-trip latency and schema version.

## Permissions

Leaf nodes default to false and are granted through two bundles:

- **`minutiae.staff`** — the day-to-day surface: issuance, lifting (vacate/pardon/served, foreign), info, docket, diagnostics, appeal review, mitigating commutation, and the standard annotation set.
- **`minutiae.admin`** (operator default) — everything, adding: aggravating commutation, waivers, `@shadow`, `@ip-lock`, `@remand`, `@backdate`, `@as`, amendment, blocking-measure lifts, cascade, bulk lift, unlift, and reload.

Notable individual nodes:

| Node | Grants |
|---|---|
| `minutiae.annotation.<name>` | Use of the named annotation |
| `minutiae.commute.mitigate` / `.aggravate` | Commutation toward a lighter / harsher measure |
| `minutiae.lift.pardon` / `.served` | The non-vacate lift kinds |
| `minutiae.lift.foreign` / `.blocking` | Lifting others' sanctions / connection-blocking sanctions |
| `minutiae.lift.cascade` / `.bulk` / `.reinstate` | Chain lift, bulk lift, unlift |
| `minutiae.appeal.submit` | Appeal submission (default true) |
| `minutiae.appeal.review` | Appeal adjudication |
| `minutiae.waive` / `minutiae.amend` | Requirement waivers, sanction amendment |
| `minutiae.notify.staff` / `minutiae.fingerprint.alert` | Staff notifications, evasion alerts |
| `minutiae.rank.<id>` | Rank attribution (scanned in configured priority order) |
| `minutiae.command.alam` | Automaton inspection and simulation |

One YAML footgun worth stating: every permission name must appear exactly once in `paper-plugin.yml`. A duplicate mapping key silently replaces its predecessor during parsing and severs the grant chain with no load-time error. If you fork the descriptor, keep the file deduplicated.

## Configuration reference

Abbreviated map of `config.yml`; the shipped file carries full inline commentary.

| Section | Governs |
|---|---|
| `messages` | Default locale, per-player locale selection, inline reason abbreviation length |
| `ranks` | Permission-to-rank-id catalogue for issuance attribution, priority-ordered |
| `broadcast` | Public announcement switch, announced measure set, lift announcements |
| `storage` | Driver (SQLITE), file path, pool capacity and timeouts |
| `boot.verbose` | Fine-grained boot diagnostics |
| `maintenance` | Sweep interval, session retention grace, frequency-aggregate refresh cadence |
| `escalation` | Probation policy: `STEP` or `MULTIPLIER` with its factor |
| `rules` / `layouts` | Registry file names |
| `annotations` | The relationship matrix: `implies`, `requires`, `conflicts` |
| `fingerprint` | Master switch, legacy weights, reverse-DNS toggle, evidence model parameters (`prior-evasion-rate`, `shrinkage-z`, `weight-cap-bits`, `flag-threshold`), CIDR network catalogue, correlation coefficients, clustering thresholds |
| `behaviour` | Quarantine anchor and radius, mute-blocked command roots, rubberband leash |
| `notify` | Channel definitions (STAFF/CONSOLE/LOG/WEBHOOK) and defaults |
| `alam` | Arming (global and per-automaton), throttle, system actor name and permission grant |
| `network` | Directive bus switch, server id, poll interval, directive TTL |
| `web` | Panel switch, bind, port, tokens |
| `chat-history` | Capture switch, mode, ring bounds, retention grace |

Runtime reload: `/me-ban reload` re-reads `config.yml` and rebuilds messages, rules, the annotation matrix, layouts, and the fingerprint model, in that dependency order. Each component swaps its published state atomically; a component that fails mid-rebuild retains its prior state, and the reload reports per-component success counts. ALAM definitions and storage settings require a restart.

## Command reference

### `/enforce` — issuance and case management

```
/enforce <spec>                                issue a sanction (grammar above)
/enforce help                                  usage
/enforce info <id>                             inspect a sanction
/enforce docket <player> [page]                a player's sanction history
/enforce lift <id> [vacate|pardon|served] [@modifiers] [reason]
/enforce unlift <id> [reason]                  reverse an erroneous lift
/enforce liftall rule|staff <value> [confirm [reason]]
/enforce appeals [page]                        pending appeals
/enforce appeal <id> accept|deny [reason]      decide an appeal
```

### `/appeal` — player-facing

```
/appeal <id> <text>                            contest your own sanction
```

### `/me-ban` — diagnostics

```
/me-ban ping                                   storage liveness and schema
/me-ban rules [id]                             rule registry
/me-ban layouts [key]                          layout registry, full inspection per key
/me-ban annotations [name]                     catalogue, scope, permission, matrix rows
/me-ban fingerprint <player>                   session signals and best evasion match
/me-ban verbose [on|off]                       per-staff report verbosity (persisted)
/me-ban reload                                 reload file-backed state
```

### `/alam` — automation

```
/alam list                                     armed rules per automaton
/alam simulate <kind> <subject> [data]         forced dry-run against every candidate
```

## Operational notes and failure posture

The system's default answer to any fault is the least destructive one:

- **Storage fault at pre-login**: the ban lookup failure is logged and the login is *permitted*. A transient database fault never locks out the player base.
- **Malformed layout, notification channel, network CIDR entry, or rank entry**: reported and skipped; siblings load. Malformed *rules* are fatal, because everything downstream validates against the registry.
- **ALAM**: an unarmed automaton is forced dry-run; a saturated one self-mutes; a guard fault is an unsatisfied guard. Automation cannot exceed its configured permission grant.
- **Reload**: a component failing mid-rebuild keeps its prior published state.
- **Networking**: directive replay is idempotent; missed directives are re-derived on join.
- **Unknown persisted values** (a measure name from a future version, an out-of-range lift-kind code): displayed verbatim or degraded to the fail-safe interpretation rather than failing the render.

Operationally load-bearing details:

- The `server-id` file must survive restarts on a networked instance; deleting it resets the directive cursor (absorbed by idempotency, but noisy).
- SQLite WAL means three files (`minutiae.db`, `-wal`, `-shm`); back up all three, or back up through the SQLite backup API.
- The maintenance sweep amortises the fingerprint frequency-aggregate rebuild; on very large sanction tables, lengthen `maintenance.frequency-refresh`.
- Audit is append-only and covers lifts, unlifts, bulk lifts, amendments, and automaton firings, each attributed to its actor (staff name, `web:<role>`, or the system actor).

## Building

```
gradlew build        # shaded jar in build/libs/
gradlew test         # language and engine test suite
gradlew runServer    # dev server (Paper 1.21.4) with the plugin loaded
```
