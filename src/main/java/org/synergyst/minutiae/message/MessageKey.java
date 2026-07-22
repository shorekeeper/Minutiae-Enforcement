package org.synergyst.minutiae.message;

import org.synergyst.minutiae.measure.Measure;

/**
 * Enumeration of every user-facing message.
 *
 * <p>Each constant binds a stable dotted path used to locate its template within
 * a bundle configuration. The enum ordinal serves as a dense array index for
 * template storage in {@link MessageBundle}, avoiding a map lookup on the send
 * path. The documented placeholders name the MiniMessage tags a template may
 * reference; callers supply matching resolvers.
 *
 * <p>Enum declaration order is not persisted and may be freely reordered; only
 * the path strings are contractual.
 */
public enum MessageKey {

    /** Message prefix, injected into other templates via the {@code <prefix>} tag. Placeholders: none. */
    PREFIX("prefix"),

    /** Command parse failure. Placeholders: {@code error}. */
    ERROR_PARSE("error.parse"),
    /** Command resolution failure. Placeholders: {@code error}. */
    ERROR_RESOLVE("error.resolve"),
    /** Sanction execution failure. Placeholders: {@code error}. */
    ERROR_ENFORCE_FAILED("error.enforce-failed"),

    /** Tokeniser: unterminated double quote. Placeholders: none. */
    PARSE_UNTERMINATED_QUOTE("parse.unterminated-quote"),
    /** Tokeniser: closing parenthesis without an opener. Placeholders: {@code offset}. */
    PARSE_UNBALANCED_CLOSE("parse.unbalanced-close"),
    /** Tokeniser: unclosed opening parentheses. Placeholders: {@code count}. */
    PARSE_UNCLOSED_OPEN("parse.unclosed-open"),
    /** Parser: more than one layout selector. Placeholders: none. */
    PARSE_MULTIPLE_LAYOUTS("parse.multiple-layouts"),
    /** Parser: empty layout selector. Placeholders: none. */
    PARSE_EMPTY_LAYOUT("parse.empty-layout"),
    /** Parser: malformed annotation token. Placeholders: {@code token}, {@code error}. */
    PARSE_MALFORMED_ANNOTATION("parse.malformed-annotation"),
    /** Parser: override with an empty key. Placeholders: {@code token}. */
    PARSE_EMPTY_OVERRIDE_KEY("parse.empty-override-key"),
    /** Parser: duplicate override key. Placeholders: {@code key}. */
    PARSE_DUPLICATE_OVERRIDE("parse.duplicate-override"),
    /** Parser: bare token after the target. Placeholders: {@code token}, {@code target}. */
    PARSE_UNEXPECTED_TOKEN("parse.unexpected-token"),
    /** Parser: no target token. Placeholders: none. */
    PARSE_NO_TARGET("parse.no-target"),


    /** Generic command-framework failure. Placeholders: {@code error}. */
    ERROR_COMMAND("error.command"),
    /** Weave parent missing during execution. Placeholders: {@code id}. */
    ERROR_WEAVE_PARENT_NOT_FOUND("error.weave-parent-not-found"),

    /** Status fragment: sanction is active. Placeholders: none. */
    STATUS_ACTIVE("status.active"),
    /** Status fragment: sanction is inactive. Placeholders: none. */
    STATUS_INACTIVE("status.inactive"),
    /** Status fragment: sanction is stayed and pending. Placeholders: none. */
    STATUS_STAYED("status.stayed"),
    /** Status fragment: sanction was lifted. Placeholders: {@code actor}. */
    STATUS_LIFTED("status.lifted"),

    /** Duration fragment: permanent sanction. Placeholders: none. */
    DURATION_PERMANENT("duration.permanent"),
    /** Duration fragment: expired sanction. Placeholders: none. */
    DURATION_EXPIRED("duration.expired"),
    /** Duration fragment: no applicable duration. Placeholders: none. */
    DURATION_NONE("duration.none"),
    /** Week unit suffix of a localised duration. Placeholders: none. */
    DURATION_UNIT_WEEKS("duration.unit.weeks"),
    /** Day unit suffix of a localised duration. Placeholders: none. */
    DURATION_UNIT_DAYS("duration.unit.days"),
    /** Hour unit suffix of a localised duration. Placeholders: none. */
    DURATION_UNIT_HOURS("duration.unit.hours"),
    /** Minute unit suffix of a localised duration. Placeholders: none. */
    DURATION_UNIT_MINUTES("duration.unit.minutes"),
    /** Second unit suffix of a localised duration. Placeholders: none. */
    DURATION_UNIT_SECONDS("duration.unit.seconds"),

    /** Kind permission denied. Placeholders: {@code kind}. */
    LIFT_DENY_KIND("lift.deny-kind"),
    /** Foreign-lift permission denied. Placeholders: {@code staff}. */
    LIFT_DENY_FOREIGN("lift.deny-foreign"),
    /** Blocking-measure lift permission denied. Placeholders: {@code measure}. */
    LIFT_DENY_BLOCKING("lift.deny-blocking"),
    /** Annotation not admissible on a lift. Placeholders: {@code name}. */
    LIFT_UNKNOWN_TOKEN("lift.unknown-token"),
    /** Probation is incompatible with a vacate. Placeholders: none. */
    LIFT_PROBATION_VACATE("lift.probation-vacate"),
    /** Dry-run lift preview line. Placeholders: {@code id}, {@code target}, {@code measure}, {@code kind}. */
    LIFT_PREVIEW("lift.preview"),
    /** Dry-run cascade extent line. Placeholders: {@code count}. */
    LIFT_PREVIEW_CASCADE("lift.preview-cascade"),
    /** Cascade completion summary. Placeholders: {@code count}. */
    LIFT_CASCADE_DONE("lift.cascade-done"),
    /** Bulk lift preview. Placeholders: {@code count}, {@code value}. */
    LIFT_BULK_PREVIEW("lift.bulk-preview"),
    /** Bulk lift with nothing to lift. Placeholders: {@code value}. */
    LIFT_BULK_EMPTY("lift.bulk-empty"),
    /** Bulk lift completion. Placeholders: {@code count}, {@code value}. */
    LIFT_BULK_DONE("lift.bulk-done"),
    /** Lift notification. Placeholders: {@code staff}, {@code rank}, {@code target}, {@code measure}, {@code id}, {@code kind}. */
    NOTIFY_LIFT("notify.lift"),
    /** Unlift confirmation. Placeholders: {@code id}, {@code target}. */
    UNLIFT_SUCCESS("unlift.success"),
    /** Unlift target is not lifted. Placeholders: {@code id}. */
    UNLIFT_NOT_LIFTED("unlift.not-lifted"),

    /** Layout fragment shown for a manually issued sanction. Placeholders: none. */
    SANCTION_LAYOUT_MANUAL("sanction.layout-manual"),
    /** Implied-annotation fragment on the flags line. Placeholders: {@code name}. */
    SANCTION_FLAG_IMPLIED("sanction.flag-implied"),

    /** Lift reason recorded when an accepted appeal lifts the sanction. Placeholders: none. */
    APPEAL_LIFT_REASON("appeal.lift-reason"),

    /** Layout reason fallback when the rule description is inherited. Placeholders: none. */
    DIAG_LAYOUT_REASON_INHERITED("diag.layout.reason-inherited"),


    /** Display name of the WARN measure. Placeholders: none. */
    MEASURE_WARN("measure.warn"),
    /** Display name of the CENSURE measure. Placeholders: none. */
    MEASURE_CENSURE("measure.censure"),
    /** Display name of the MUTE measure. Placeholders: none. */
    MEASURE_MUTE("measure.mute"),
    /** Display name of the KICK measure. Placeholders: none. */
    MEASURE_KICK("measure.kick"),
    /** Display name of the QUARANTINE measure. Placeholders: none. */
    MEASURE_QUARANTINE("measure.quarantine"),
    /** Display name of the SUSPENSION measure. Placeholders: none. */
    MEASURE_SUSPENSION("measure.suspension"),
    /** Display name of the CUSTODY measure. Placeholders: none. */
    MEASURE_CUSTODY("measure.custody"),

    /** Resolve diagnostics. Each carries its own prefix and styling. */
    RESOLVE_DIRECTIVE_NEGATED("resolve.directive-negated"),
    RESOLVE_DUPLICATE_DIRECTIVE("resolve.duplicate-directive"),
    RESOLVE_COUNT_UNKNOWN_RULE("resolve.count-unknown-rule"),
    RESOLVE_WAIVE_UNKNOWN("resolve.waive-unknown"),
    RESOLVE_UNHANDLED_DIRECTIVE("resolve.unhandled-directive"),
    RESOLVE_AMEND_CONFLICT("resolve.amend-conflict"),
    RESOLVE_TARIFF_TEMPORAL("resolve.tariff-temporal"),
    RESOLVE_UNKNOWN_LAYOUT("resolve.unknown-layout"),
    RESOLVE_UNKNOWN_ANNOTATION("resolve.unknown-annotation"),
    RESOLVE_NO_PERMISSION("resolve.no-permission"),
    RESOLVE_REMAND_STANDALONE("resolve.remand-standalone"),
    RESOLVE_MEASURE_COMMUTE_EXCLUSIVE("resolve.measure-commute-exclusive"),
    RESOLVE_NOTHING_TO_COMMUTE("resolve.nothing-to-commute"),
    RESOLVE_LAYOUT_HAS_MEASURE("resolve.layout-has-measure"),
    RESOLVE_NO_MEASURE("resolve.no-measure"),
    RESOLVE_COMMUTE_MITIGATE("resolve.commute-mitigate"),
    RESOLVE_COMMUTE_AGGRAVATE("resolve.commute-aggravate"),
    RESOLVE_CONFLICT("resolve.conflict"),
    RESOLVE_REQUIRES("resolve.requires"),
    RESOLVE_UNKNOWN_OVERRIDE("resolve.unknown-override"),
    RESOLVE_INSTANT_NO_DURATION("resolve.instant-no-duration"),
    RESOLVE_INVALID_DURATION("resolve.invalid-duration"),
    RESOLVE_MEASURE_NEEDS_DURATION("resolve.measure-needs-duration"),
    RESOLVE_WEAVE_FORMAT("resolve.weave-format"),
    RESOLVE_WEAVE_POSITIVE("resolve.weave-positive"),

    /** Suspended-sentence header. Placeholders: none. */
    SANCTION_SUSPENDED("sanction.suspended"),
    /** Suspended-sentences-activated line. Placeholders: {@code count}. */
    LINE_SUSPENDED_ACTIVATED("sanction.line.suspended-activated"),
    /** Sanction-review-due notification. Placeholders: {@code id}, {@code measure}, {@code staff}. */
    NOTIFY_REVIEW("notify.review"),
    /** Suspend/stay exclusivity diagnostic. Placeholders: none. */
    RESOLVE_SUSPEND_STAY("resolve.suspend-stay"),

    /** Enforce command usage. Placeholders: {@code measures}, {@code layouts}. */
    ENFORCE_HELP("enforce.help"),

    /** Applied-sanction header. Placeholders: none. */
    SANCTION_APPLIED("sanction.applied"),
    /** Stayed-sanction header. Placeholders: none. */
    SANCTION_STAYED("sanction.stayed"),
    /** Dry-run header. Placeholders: none. */
    SANCTION_DRY_RUN("sanction.dry-run"),
    /** Dry-run footer. Placeholders: none. */
    SANCTION_DRY_RUN_FOOTER("sanction.dry-run-footer"),

    /** Notice shown when a muted player attempts to speak or use a blocked command. Placeholders: none. */
    BEHAVIOUR_MUTED("behaviour.muted"),
    /** Notice shown when a player is confined to quarantine. Placeholders: none. */
    BEHAVIOUR_QUARANTINE_APPLIED("behaviour.quarantine.applied"),
    /** Notice shown when a quarantined player reaches the confinement boundary. Placeholders: none. */
    BEHAVIOUR_QUARANTINE_BOUNDARY("behaviour.quarantine.boundary"),

    /** Target line. Placeholders: {@code target}. */
    LINE_TARGET("sanction.line.target"),
    /** Measure line. Placeholders: {@code measure}. */
    LINE_MEASURE("sanction.line.measure"),
    /** Layout line. Placeholders: {@code layout}. */
    LINE_LAYOUT("sanction.line.layout"),
    /** Rule line. Placeholders: {@code rule}. */
    LINE_RULE("sanction.line.rule"),
    /** Reason line. Placeholders: {@code reason}. */
    LINE_REASON("sanction.line.reason"),
    /** Duration line. Placeholders: {@code duration}. */
    LINE_DURATION("sanction.line.duration"),
    /** Escalated-duration line. Placeholders: {@code duration}, {@code prior}. */
    LINE_DURATION_ESCALATED("sanction.line.duration-escalated"),
    /** Stay line. Placeholders: {@code stay}. */
    LINE_STAY("sanction.line.stay"),
    /** Flags line. Placeholders: {@code flags}. */
    LINE_FLAGS("sanction.line.flags"),
    /** Identifier line. Placeholders: {@code id}. */
    LINE_ID("sanction.line.id"),
    /** Stays-activated line. Placeholders: {@code count}. */
    LINE_STAYS_ACTIVATED("sanction.line.stays-activated"),

    /** Warning delivered to the target. Placeholders: {@code reason}. */
    MECHANISM_WARN("mechanism.warn"),
    /** Kick screen for punitive kicks. Placeholders: {@code reason}. */
    MECHANISM_KICK("mechanism.kick"),

    /** Connection-refusal screen. Placeholders: {@code reason}, {@code measure}. */
    ACCESS_BANNED("access.banned"),

    /** Formal-warning header shown when a warn-first gate downgrades an offence. Placeholders: none. */
    SANCTION_WARNING("sanction.warning"),
    /** Warning-progress line. Placeholders: {@code count}, {@code required}. */
    LINE_WARNING("sanction.line.warning"),
    /** Notification summary. Placeholders: {@code staff}, {@code measure}, {@code target}, {@code rule}, {@code duration}, {@code reason}. */
    NOTIFY_SUMMARY("notify.summary"),

    /** Compact one-line executor report. Placeholders: {@code target}, {@code measure}, {@code duration}, {@code reason}. */
    SANCTION_COMPACT("sanction.compact"),
    /** Public sanction broadcast. Placeholders: {@code target}, {@code staff}, {@code measure}, {@code duration}, {@code reason}. */
    BROADCAST_SANCTION("broadcast.sanction"),
    /** Inline reason display. Placeholders: {@code short}. */
    REASON_DISPLAY("reason.display"),
    /** Reason hover card. Placeholders: {@code reason}, {@code measure}, {@code rule}, {@code duration}, {@code staff}, {@code when}. */
    REASON_CARD("reason.card"),
    /** Verbose-reports-enabled notice. Placeholders: none. */
    PREF_VERBOSE_ON("pref.verbose-on"),
    /** Verbose-reports-disabled notice. Placeholders: none. */
    PREF_VERBOSE_OFF("pref.verbose-off"),
    /** Console-verbose notice. Placeholders: none. */
    PREF_CONSOLE("pref.console"),
    /** Display name of the vacate lift kind. Placeholders: none. */
    LIFT_KIND_VACATE("lift.kind.vacate"),
    /** Display name of the pardon lift kind. Placeholders: none. */
    LIFT_KIND_PARDON("lift.kind.pardon"),
    /** Display name of the time-served lift kind. Placeholders: none. */
    LIFT_KIND_TIME_SERVED("lift.kind.time-served"),
    /** Lift confirmation to the actor. Placeholders: {@code id}, {@code target}, {@code measure}, {@code actor}. */
    LIFT_SUCCESS("lift.success"),
    /** Sanction-not-found notice. Placeholders: {@code id}. */
    LIFT_NOT_FOUND("lift.not-found"),
    /** Already-lifted notice. Placeholders: {@code id}. */
    LIFT_ALREADY("lift.already"),
    /** Public lift announcement. Placeholders: {@code target}, {@code staff}, {@code measure}. */
    LIFT_BROADCAST("lift.broadcast"),
    /** Notice to a player whose sanction was lifted. Placeholders: none. */
    BEHAVIOUR_LIFTED("behaviour.lifted"),
    /** Sanction-info header. Placeholders: {@code id}. */
    INFO_HEADER("info.header"),
    /** Sanction-info status line. Placeholders: {@code status}. */
    INFO_STATUS("info.status"),
    /** Sanction-info staff line. Placeholders: {@code staff}. */
    INFO_STAFF("info.staff"),
    /** Docket header. Placeholders: {@code player}, {@code total}, {@code page}, {@code pages}. */
    DOCKET_HEADER("docket.header"),
    /** Docket empty notice. Placeholders: none. */
    DOCKET_EMPTY("docket.empty"),
    /** Docket entry line. Placeholders: {@code id}, {@code measure}, {@code rule}, {@code status}, {@code reason}. */
    DOCKET_ENTRY("docket.entry"),
    /** Unknown-player notice. Placeholders: {@code player}. */
    DOCKET_UNKNOWN("docket.unknown"),
    /** Reload result. Placeholders: {@code ok}, {@code failed}. */
    RELOAD_DONE("reload.done"),
    /** Amend confirmation. Placeholders: {@code id}, {@code diff}. */
    SANCTION_AMENDED("sanction.amended"),
    /** Docket woven-under line. Placeholders: {@code parent}. */
    DOCKET_WOVEN("docket.woven"),

    /** Appeal must be submitted by a player. Placeholders: none. */
    APPEAL_PLAYER_ONLY("appeal.player-only"),
    /** Appeal targets another player's sanction. Placeholders: none. */
    APPEAL_NOT_YOURS("appeal.not-yours"),
    /** Sanction is not appealable. Placeholders: none. */
    APPEAL_NOT_APPEALABLE("appeal.not-appealable"),
    /** Sanction already lifted. Placeholders: none. */
    APPEAL_ALREADY_LIFTED("appeal.already-lifted"),
    /** A pending appeal already exists. Placeholders: {@code id}. */
    APPEAL_DUPLICATE("appeal.duplicate"),
    /** Appeal submitted. Placeholders: {@code id}, {@code appeal}. */
    APPEAL_SUBMITTED("appeal.submitted"),
    /** Appeal not found. Placeholders: {@code id}. */
    APPEAL_NOT_FOUND("appeal.not-found"),
    /** Appeal already decided. Placeholders: {@code id}. */
    APPEAL_ALREADY_DECIDED("appeal.already-decided"),
    /** Appeal accepted. Placeholders: {@code id}. */
    APPEAL_ACCEPTED("appeal.accepted"),
    /** Appeal denied. Placeholders: {@code id}. */
    APPEAL_DENIED("appeal.denied"),
    /** Pending appeals header. Placeholders: {@code total}, {@code page}, {@code pages}. */
    APPEALS_HEADER("appeals.header"),
    /** No pending appeals. Placeholders: none. */
    APPEALS_EMPTY("appeals.empty"),
    /** Pending appeal entry. Placeholders: {@code appeal}, {@code id}, {@code appellant}, {@code text}. */
    APPEALS_ENTRY("appeals.entry"),
    /** New-appeal notification. Placeholders: {@code player}, {@code id}, {@code appeal}, {@code text}. */
    NOTIFY_APPEAL("notify.appeal"),

    /** Evasion alert broadcast. Placeholders: {@code name}, {@code score}, {@code banned}. */
    FINGERPRINT_ALERT("fingerprint.alert"),
    /** Fingerprint inspection header. Placeholders: {@code name}. */
    FINGERPRINT_HEADER("fingerprint.header"),
    /** No-session notice. Placeholders: none. */
    FINGERPRINT_NO_SESSION("fingerprint.no-session"),
    /** Signal line. Placeholders: {@code label}, {@code value}. */
    FINGERPRINT_LINE("fingerprint.line"),
    /** No-match notice. Placeholders: none. */
    FINGERPRINT_NO_MATCH("fingerprint.no-match"),
    /** Match notice. Placeholders: {@code banned}, {@code score}, {@code signals}. */
    FINGERPRINT_MATCH("fingerprint.match"),
    /** Target-offline notice. Placeholders: {@code name}. */
    FINGERPRINT_NOT_ONLINE("fingerprint.not-online"),

    /** Storage probe start. Placeholders: none. */
    DIAG_PING_PROBING("diag.ping.probing"),
    /** Storage probe success. Placeholders: {@code latency}, {@code schema}. */
    DIAG_PING_OK("diag.ping.ok"),
    /** Storage probe failure. Placeholders: none. */
    DIAG_PING_UNAVAILABLE("diag.ping.unavailable"),

    /** Rule count. Placeholders: {@code count}. */
    DIAG_RULES_COUNT("diag.rules.count"),
    /** Single rule. Placeholders: {@code id}, {@code description}. */
    DIAG_RULES_ENTRY("diag.rules.entry"),
    /** Unknown rule. Placeholders: {@code id}. */
    DIAG_RULES_UNDEFINED("diag.rules.undefined"),

    /** Layout list. Placeholders: {@code count}, {@code keys}. */
    DIAG_LAYOUTS_LIST("diag.layouts.list"),
    /** Unknown layout. Placeholders: {@code key}. */
    DIAG_LAYOUTS_UNKNOWN("diag.layouts.unknown"),
    /** Layout header. Placeholders: {@code key}. */
    DIAG_LAYOUT_HEADER("diag.layout.header"),
    /** Layout measure line. Placeholders: {@code measure}. */
    DIAG_LAYOUT_MEASURE("diag.layout.measure"),
    /** Layout rule line. Placeholders: {@code rule}, {@code description}. */
    DIAG_LAYOUT_RULE("diag.layout.rule"),
    /** Layout reason line. Placeholders: {@code reason}. */
    DIAG_LAYOUT_REASON("diag.layout.reason"),
    /** Layout duration line. Placeholders: {@code duration}. */
    DIAG_LAYOUT_DURATION("diag.layout.duration"),
    /** Layout escalation line. Placeholders: {@code ladder}. */
    DIAG_LAYOUT_ESCALATION("diag.layout.escalation"),
    /** Layout flags line. Placeholders: {@code flags}. */
    DIAG_LAYOUT_FLAGS("diag.layout.flags"),

    /** Annotation list. Placeholders: {@code count}, {@code names}. */
    DIAG_ANNOTATIONS_LIST("diag.annotations.list"),
    /** Unknown annotation. Placeholders: {@code name}. */
    DIAG_ANNOTATION_UNKNOWN("diag.annotation.unknown"),
    /** Annotation header. Placeholders: {@code name}. */
    DIAG_ANNOTATION_HEADER("diag.annotation.header"),
    /** Annotation scope line. Placeholders: {@code scope}. */
    DIAG_ANNOTATION_SCOPE("diag.annotation.scope"),
    /** Annotation permission line. Placeholders: {@code permission}. */
    DIAG_ANNOTATION_PERMISSION("diag.annotation.permission"),
    /** Annotation implies line. Placeholders: {@code names}. */
    DIAG_ANNOTATION_IMPLIES("diag.annotation.implies"),
    /** Annotation requires line. Placeholders: {@code names}. */
    DIAG_ANNOTATION_REQUIRES("diag.annotation.requires"),
    /** Annotation conflicts line. Placeholders: {@code names}. */
    DIAG_ANNOTATION_CONFLICTS("diag.annotation.conflicts"),
    /** Automaton list header. Placeholders: {@code count}. */
    ALAM_LIST_HEADER("alam.list.header"),
    /** Automaton list entry. Placeholders: {@code automaton}, {@code rule}, {@code event}, {@code stages}. */
    ALAM_LIST_ENTRY("alam.list.entry"),
    /** Simulation header. Placeholders: {@code kind}, {@code subject}. */
    ALAM_SIM_HEADER("alam.sim.header"),
    /** Simulated firing. Placeholders: {@code automaton}, {@code rule}. */
    ALAM_SIM_FIRE("alam.sim.fire"),
    /** Simulated skip. Placeholders: {@code automaton}, {@code rule}, {@code decision}. */
    ALAM_SIM_SKIP("alam.sim.skip"),
    /** No rule fired in simulation. Placeholders: none. */
    ALAM_SIM_EMPTY("alam.sim.empty"),
    /** Unknown event kind. Placeholders: {@code kind}. */
    ALAM_SIM_UNKNOWN_KIND("alam.sim.unknown-kind");

    private final String path;

    MessageKey(final String path) {
        this.path = path;
    }
    /**
     * Resolves the display key for a measure.
     *
     * @param measure the measure
     * @return the measure display key
     */
    public static MessageKey measureKey(final Measure measure) {
        return switch (measure) {
            case WARN -> MEASURE_WARN;
            case CENSURE -> MEASURE_CENSURE;
            case MUTE -> MEASURE_MUTE;
            case KICK -> MEASURE_KICK;
            case QUARANTINE -> MEASURE_QUARANTINE;
            case SUSPENSION -> MEASURE_SUSPENSION;
            case CUSTODY -> MEASURE_CUSTODY;
        };
    }

    /**
     * Resolves the display key for a persisted measure name.
     *
     * <p>Measure names reach display sites as raw strings read from storage;
     * a name that matches no known measure (a value written by a future
     * version, or a corrupted row) yields null, and the caller falls back to
     * showing the raw name rather than failing the render.
     *
     * @param measureName the measure name, case-insensitive, possibly null
     * @return the measure display key, or null when the name is unrecognised
     */
    public static MessageKey measureKey(final String measureName) {
        if (measureName == null) {
            return null;
        }
        try {
            return measureKey(Measure.parse(measureName));
        } catch (final IllegalArgumentException unknown) {
            return null;
        }
    }

    /**
     * Resolves the display key for a lift kind.
     *
     * @param kind the lift kind
     * @return the kind display key
     */
    public static MessageKey liftKindKey(final org.synergyst.minutiae.execute.LiftKind kind) {
        return switch (kind) {
            case VACATE -> LIFT_KIND_VACATE;
            case PARDON -> LIFT_KIND_PARDON;
            case TIME_SERVED -> LIFT_KIND_TIME_SERVED;
        };
    }

    /** Returns the dotted configuration path locating this message's template. */
    public String path() {
        return path;
    }
}