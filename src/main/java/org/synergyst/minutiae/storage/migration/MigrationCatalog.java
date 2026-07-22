package org.synergyst.minutiae.storage.migration;

/**
 * Ordered registry of schema migrations for the SQLite backend.
 *
 * <p>The catalogue is a static, append-only list. Entries are never edited or
 * removed once released; corrections are expressed as new higher-versioned
 * migrations. The array is returned by reference for iteration; callers must
 * not mutate it.
 *
 * <p>Schema overview after the latest migration:
 * <ul>
 *   <li>{@code bans} - one row per issued sanction. Timestamps are stored as
 *       epoch milliseconds. {@code expires_at} of 0 denotes no expiry (permanent
 *       or instantaneous). {@code active}, {@code stayed}, {@code provisional},
 *       and {@code appealable} are 0/1 flags. {@code measure} names the
 *       enforcement mechanism. {@code stay_until} bounds a deferred sanction's
 *       activation window. {@code behaviour_mask} carries the sanction's
 *       in-session behavioural constraints. {@code decay_at} of 0 denotes a
 *       sanction that never ages out of precedent; any other value is the epoch
 *       millisecond after which the sanction ceases to count toward precedent.
 *       {@code annotations} holds the resolved annotation set as an opaque
 *       display string. {@code link} holds an optional external reference.
 *       {@code lift_kind} classifies a lift as vacate (0), pardon (1), or
 *       time-served (2); the zero default preserves the pre-migration
 *       precedent semantics of historical lifts, which were unconditionally
 *       excluded. No dedicated index is added: the precedent query is already
 *       narrowed by {@code idx_bans_precedent} on {@code (uuid, rule,
 *       decay_at)}, against which the lift predicates are cheap residual
 *       filters over a handful of rows.</li>
 *   <li>{@code provisions} - additional cited rules for a sanction, modelled
 *       one-to-many against {@code bans}, supporting multi-count sanctions.</li>
 *   <li>{@code signals} - fingerprint signals attached to a sanction, modelled
 *       one-to-many against {@code bans}. {@code type} is a compact integer code
 *       (a signal-type ordinal) rather than a string, minimising row width and
 *       index size. {@code weight} is the signal's scoring contribution captured
 *       at issue time.</li>
 *   <li>{@code rules_cache} - denormalised copy of the rule registry used for
 *       referential validation and historical display. {@code content_hash}
 *       allows cheap staleness detection.</li>
 *   <li>{@code schema_version} - single-row bookkeeping table recording the
 *       highest applied migration version.</li>
 * </ul>
 */
public final class MigrationCatalog {

    private static final Migration[] MIGRATIONS = {

            new Migration(1, "baseline schema", new String[]{
                    """
                    CREATE TABLE IF NOT EXISTS schema_version (
                        singleton INTEGER PRIMARY KEY CHECK (singleton = 0),
                        version   INTEGER NOT NULL
                    )
                    """,
                    """
                    INSERT OR IGNORE INTO schema_version (singleton, version) VALUES (0, 0)
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS bans (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid        TEXT    NOT NULL,
                        layout      TEXT,
                        rule        TEXT,
                        reason      TEXT,
                        issued_at   INTEGER NOT NULL,
                        expires_at  INTEGER NOT NULL DEFAULT 0,
                        staff       TEXT    NOT NULL,
                        annotations TEXT    NOT NULL DEFAULT '',
                        active      INTEGER NOT NULL DEFAULT 1
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_bans_uuid   ON bans (uuid)",
                    "CREATE INDEX IF NOT EXISTS idx_bans_active ON bans (active)",
                    "CREATE INDEX IF NOT EXISTS idx_bans_expiry ON bans (expires_at)",
                    """
                    CREATE TABLE IF NOT EXISTS signals (
                        ban_id INTEGER NOT NULL,
                        type   INTEGER NOT NULL,
                        value  TEXT    NOT NULL,
                        weight REAL    NOT NULL,
                        FOREIGN KEY (ban_id) REFERENCES bans (id) ON DELETE CASCADE
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_signals_ban    ON signals (ban_id)",
                    "CREATE INDEX IF NOT EXISTS idx_signals_lookup ON signals (type, value)",
                    """
                    CREATE TABLE IF NOT EXISTS rules_cache (
                        rule         TEXT    PRIMARY KEY,
                        description  TEXT    NOT NULL,
                        content_hash INTEGER NOT NULL
                    )
                    """
            }),

            new Migration(2, "measure, stays, provisions", new String[]{
                    "ALTER TABLE bans ADD COLUMN measure    TEXT    NOT NULL DEFAULT 'CUSTODY'",
                    "ALTER TABLE bans ADD COLUMN stayed     INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE bans ADD COLUMN stay_until INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE bans ADD COLUMN link       TEXT",
                    "CREATE INDEX IF NOT EXISTS idx_bans_uuid_rule ON bans (uuid, rule)",
                    """
                    CREATE TABLE IF NOT EXISTS provisions (
                        ban_id INTEGER NOT NULL,
                        rule   TEXT    NOT NULL,
                        FOREIGN KEY (ban_id) REFERENCES bans (id) ON DELETE CASCADE
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_provisions_ban ON provisions (ban_id)"
            }),

            new Migration(3, "behaviour mask and provisional flag", new String[]{
                    "ALTER TABLE bans ADD COLUMN behaviour_mask INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE bans ADD COLUMN provisional    INTEGER NOT NULL DEFAULT 0",
                    "CREATE INDEX IF NOT EXISTS idx_bans_behaviour ON bans (uuid, behaviour_mask)"
            }),

            new Migration(4, "decay window and appealability", new String[]{
                    "ALTER TABLE bans ADD COLUMN decay_at   INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE bans ADD COLUMN appealable INTEGER NOT NULL DEFAULT 1",
                    "CREATE INDEX IF NOT EXISTS idx_bans_precedent ON bans (uuid, rule, decay_at)"
            }),

            new Migration(5, "staff preferences", new String[]{
                    """
                    CREATE TABLE IF NOT EXISTS preferences (
                        uuid    TEXT    PRIMARY KEY,
                        verbose INTEGER NOT NULL DEFAULT 1
                    )
                    """
            }),
            new Migration(6, "lift metadata and audit log", new String[]{
                    "ALTER TABLE bans ADD COLUMN lifted_at   INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE bans ADD COLUMN lifted_by   TEXT",
                    "ALTER TABLE bans ADD COLUMN lift_reason TEXT",
                    """
                    CREATE TABLE IF NOT EXISTS audit (
                        id     INTEGER PRIMARY KEY AUTOINCREMENT,
                        ban_id INTEGER,
                        action TEXT    NOT NULL,
                        actor  TEXT    NOT NULL,
                        ts     INTEGER NOT NULL,
                        detail TEXT
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_audit_ban ON audit (ban_id)"
            }),
            new Migration(7, "appeals", new String[]{
                    """
                    CREATE TABLE IF NOT EXISTS appeals (
                        id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        ban_id     INTEGER NOT NULL,
                        appellant  TEXT    NOT NULL,
                        text       TEXT    NOT NULL,
                        status     TEXT    NOT NULL DEFAULT 'PENDING',
                        verdict    TEXT,
                        reviewer   TEXT,
                        created_at INTEGER NOT NULL,
                        decided_at INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (ban_id) REFERENCES bans (id) ON DELETE CASCADE
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_appeals_ban    ON appeals (ban_id)",
                    "CREATE INDEX IF NOT EXISTS idx_appeals_status ON appeals (status)"
            }),

            new Migration(8, "joinder and probation", new String[]{
                    "ALTER TABLE bans ADD COLUMN parent_id       INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE bans ADD COLUMN probation_until INTEGER NOT NULL DEFAULT 0",
                    "CREATE INDEX IF NOT EXISTS idx_bans_parent    ON bans (parent_id)",
                    "CREATE INDEX IF NOT EXISTS idx_bans_probation ON bans (uuid, rule, probation_until)"
            }),
            new Migration(9, "chat transcripts and forensic tables", new String[]{
                    """
                    CREATE TABLE IF NOT EXISTS chat_transcript (
                        ban_id  INTEGER NOT NULL,
                        seq     INTEGER NOT NULL,
                        ts      INTEGER NOT NULL,
                        message TEXT    NOT NULL,
                        FOREIGN KEY (ban_id) REFERENCES bans (id) ON DELETE CASCADE
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_transcript_ban ON chat_transcript (ban_id)",
                    """
                    CREATE TABLE IF NOT EXISTS sanction_trace (
                        ban_id          INTEGER PRIMARY KEY,
                        prior_sanctions INTEGER NOT NULL DEFAULT 0,
                        prior_warnings  INTEGER NOT NULL DEFAULT 0,
                        in_probation    INTEGER NOT NULL DEFAULT 0,
                        escalated       INTEGER NOT NULL DEFAULT 0,
                        ladder_index    INTEGER NOT NULL DEFAULT 0,
                        base_ms         INTEGER NOT NULL DEFAULT 0,
                        final_ms        INTEGER NOT NULL DEFAULT 0,
                        warn_downgrade  INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (ban_id) REFERENCES bans (id) ON DELETE CASCADE
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS ip_history (
                        uuid       TEXT    NOT NULL,
                        ip         TEXT    NOT NULL,
                        first_seen INTEGER NOT NULL,
                        last_seen  INTEGER NOT NULL,
                        hits       INTEGER NOT NULL DEFAULT 1,
                        PRIMARY KEY (uuid, ip)
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_iphist_ip ON ip_history (ip)",
                    """
                    CREATE TABLE IF NOT EXISTS case_notes (
                        id       INTEGER PRIMARY KEY AUTOINCREMENT,
                        ban_id   INTEGER,
                        uuid     TEXT,
                        author   TEXT    NOT NULL,
                        text     TEXT    NOT NULL,
                        ts       INTEGER NOT NULL,
                        FOREIGN KEY (ban_id) REFERENCES bans (id) ON DELETE CASCADE
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_notes_ban ON case_notes (ban_id)",
                    "CREATE INDEX IF NOT EXISTS idx_notes_uuid ON case_notes (uuid)",
                    """
                    CREATE TABLE IF NOT EXISTS alam_firings (
                        id        INTEGER PRIMARY KEY AUTOINCREMENT,
                        automaton TEXT    NOT NULL,
                        rule      TEXT    NOT NULL,
                        event     TEXT    NOT NULL,
                        subject   TEXT,
                        decision  TEXT    NOT NULL,
                        dry_run   INTEGER NOT NULL DEFAULT 0,
                        ban_id    INTEGER,
                        ts        INTEGER NOT NULL
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_firings_automaton ON alam_firings (automaton)"
            }),

            new Migration(11, "subject names and username history", new String[]{
                    "ALTER TABLE bans ADD COLUMN subject_name TEXT NOT NULL DEFAULT ''",
                    """
                    CREATE TABLE IF NOT EXISTS usernames (
                        uuid       TEXT    NOT NULL,
                        name       TEXT    NOT NULL,
                        first_seen INTEGER NOT NULL,
                        last_seen  INTEGER NOT NULL,
                        PRIMARY KEY (uuid, name)
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_usernames_uuid ON usernames (uuid)",
                    "CREATE INDEX IF NOT EXISTS idx_usernames_name ON usernames (name)"
            }),

            new Migration(12, "fingerprint aggregates, session intervals, activity, beliefs",
                    new String[]{
                            """
                    CREATE TABLE IF NOT EXISTS signal_freq (
                        type          INTEGER NOT NULL,
                        value         TEXT    NOT NULL,
                        account_count INTEGER NOT NULL,
                        PRIMARY KEY (type, value)
                    )
                    """,
                            """
                    CREATE TABLE IF NOT EXISTS corpus_stat (
                        singleton      INTEGER PRIMARY KEY CHECK (singleton = 0),
                        total_accounts INTEGER NOT NULL DEFAULT 0
                    )
                    """,
                            "INSERT OR IGNORE INTO corpus_stat (singleton, total_accounts) VALUES (0, 0)",
                            """
                    CREATE TABLE IF NOT EXISTS session_interval (
                        id        INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid      TEXT    NOT NULL,
                        ip        TEXT,
                        login_at  INTEGER NOT NULL,
                        logout_at INTEGER NOT NULL DEFAULT 0
                    )
                    """,
                            "CREATE INDEX IF NOT EXISTS idx_sess_uuid ON session_interval (uuid)",
                            "CREATE INDEX IF NOT EXISTS idx_sess_ip   ON session_interval (ip)",
                            "CREATE INDEX IF NOT EXISTS idx_sess_open ON session_interval (uuid, logout_at)",
                            """
                    CREATE TABLE IF NOT EXISTS activity_hist (
                        uuid TEXT    NOT NULL,
                        hour INTEGER NOT NULL,
                        hits INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (uuid, hour)
                    )
                    """,
                            """
                    CREATE TABLE IF NOT EXISTS signal_belief (
                        type       INTEGER PRIMARY KEY,
                        alpha      REAL    NOT NULL,
                        beta       REAL    NOT NULL,
                        updated_at INTEGER NOT NULL DEFAULT 0
                    )
                    """
                    }),
            new Migration(13, "network directives and issuance attribution", new String[]{
                    "ALTER TABLE bans ADD COLUMN server_id TEXT NOT NULL DEFAULT ''",
                    """
                    CREATE TABLE IF NOT EXISTS directives (
                        id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        origin     TEXT    NOT NULL,
                        target     TEXT,
                        kind       TEXT    NOT NULL,
                        subject    TEXT    NOT NULL DEFAULT '',
                        payload    TEXT    NOT NULL DEFAULT '',
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_directives_expiry ON directives (expires_at)",
                    """
                    CREATE TABLE IF NOT EXISTS directive_cursor (
                        server_id TEXT    PRIMARY KEY,
                        last_id   INTEGER NOT NULL DEFAULT 0
                    )
                    """
            }),
            new Migration(14, "procedural windows: suspension, review, expungement",
                    new String[]{
                            "ALTER TABLE bans ADD COLUMN suspended     INTEGER NOT NULL DEFAULT 0",
                            "ALTER TABLE bans ADD COLUMN suspend_until INTEGER NOT NULL DEFAULT 0",
                            "ALTER TABLE bans ADD COLUMN expunge_at    INTEGER NOT NULL DEFAULT 0",
                            "ALTER TABLE bans ADD COLUMN review_at     INTEGER NOT NULL DEFAULT 0",
                            "CREATE INDEX IF NOT EXISTS idx_bans_suspended"
                                    + " ON bans (uuid, rule, suspended)",
                            "CREATE INDEX IF NOT EXISTS idx_bans_review ON bans (review_at)"
                    }),
            new Migration(15, "lift kinds", new String[]{
                    "ALTER TABLE bans ADD COLUMN lift_kind INTEGER NOT NULL DEFAULT 0"
            })
    };

    private MigrationCatalog() {
    }

    /**
     * Returns the ordered migration array. The returned reference is the live
     * internal array and must be treated as read-only.
     *
     * @return the migration catalogue
     */
    public static Migration[] migrations() {
        return MIGRATIONS;
    }

    /**
     * Returns the highest migration version defined by the catalogue.
     *
     * @return the target schema version
     */
    public static int targetVersion() {
        return MIGRATIONS.length == 0 ? 0 : MIGRATIONS[MIGRATIONS.length - 1].version();
    }
}