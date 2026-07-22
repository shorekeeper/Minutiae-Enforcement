package org.synergyst.minutiae.storage;

import org.synergyst.minutiae.lifecycle.LifecycleComponent;

import java.util.concurrent.CompletableFuture;

/**
 * Backend-agnostic persistence contract.
 *
 * <p>Implementations own their physical resources (connection pools, drivers)
 * and expose a narrow asynchronous surface. No method on this interface may
 * block the calling thread on I/O; blocking work is delegated to the injected
 * asynchronous scheduler and surfaced through {@link CompletableFuture}.
 *
 * <p>Lifecycle ordering: {@link #boot()} opens the backend, applies pending
 * schema migrations, and verifies readiness before returning. {@link #shutdown()}
 * drains and closes all physical resources.
 */
public interface Storage extends LifecycleComponent {

    /**
     * Reports the schema version currently applied to the backend.
     *
     * @return the applied schema version; a value of 0 indicates a freshly
     *         initialised, unmigrated database
     */
    int schemaVersion();

    /**
     * Issues a liveness probe against the backend.
     *
     * <p>The probe executes a trivial round-trip query on a scheduler thread
     * and measures its wall-clock duration. The returned stage never completes
     * exceptionally; failures are encoded in the {@link StoragePing#ok()} flag.
     *
     * @return a stage yielding the probe result
     */
    CompletableFuture<StoragePing> ping();

    /**
     * Reconciles the persisted rule cache against an authoritative rule set.
     *
     * <p>The three arrays are parallel: element {@code i} of each describes one
     * rule. The operation executes as a single transaction: absent identifiers
     * are inserted, identifiers whose content hash differs are updated,
     * identifiers already current are left untouched, and cached identifiers
     * absent from the input are deleted. On failure the transaction is rolled
     * back and the stage completes exceptionally.
     *
     * @param ids          rule identifiers
     * @param descriptions rule descriptions, parallel to {@code ids}
     * @param hashes       content hashes, parallel to {@code ids}
     * @return a stage yielding the reconciliation counts
     */
    CompletableFuture<RuleSyncResult> syncRuleCache(String[] ids, String[] descriptions, int[] hashes);

    /**
     * Persists a sanction and its additional provisions atomically.
     *
     * @param row               the primary sanction row
     * @param extraProvisions   additional rule identifiers cited beyond the
     *                          primary rule; may be empty
     * @return a stage yielding the generated row identifier
     */
    CompletableFuture<Long> persistSanction(SanctionRow row, String[] extraProvisions);

    /**
     * Resolves precedent counts for a player under a rule, disregarding decayed
     * sanctions.
     *
     * <p>Non-warning sanctions and warnings are counted separately. A null rule,
     * or a sanction with no cited rule, yields {@link Precedent#NONE}. Only
     * sanctions whose decay window is zero or lies in the future contribute.
     *
     * @param uuid target UUID string
     * @param rule rule identifier, or null
     * @param now  current timestamp in epoch milliseconds
     * @return a stage yielding the precedent counts
     */
    CompletableFuture<Precedent> precedent(String uuid, String rule, long now);

    /**
     * Activates any stayed sanctions for a player whose stay window has not
     * elapsed, transitioning them from deferred to active.
     *
     * @param uuid target UUID string
     * @param now  current timestamp in epoch milliseconds
     * @return a stage yielding the number of sanctions activated
     */
    CompletableFuture<Integer> activateStays(String uuid, long now);

    /**
     * Resolves the most severe active connection-blocking sanction for a player.
     *
     * @param uuid target UUID string
     * @param now  current timestamp in epoch milliseconds
     * @return a stage yielding the blocking sanction, or null when none applies
     */
    CompletableFuture<ActiveBan> activeConnectionBan(String uuid, long now);

    /**
     * Persists a batch of fingerprint signals for a sanction.
     *
     * <p>The three arrays are parallel; element {@code i} of each describes one
     * signal. An empty batch is a no-op that completes successfully.
     *
     * @param banId   the owning sanction identifier
     * @param types   signal type codes
     * @param values  signal values, parallel to {@code types}
     * @param weights capture-time weights, parallel to {@code types}
     * @return a stage completing when the batch is persisted
     */
    CompletableFuture<Void> persistSignals(long banId, int[] types, String[] values, double[] weights);

    /**
     * Scores a login probe against signals recorded for active
     * connection-blocking sanctions.
     *
     * <p>The probe arrays are parallel. The operation matches each probe signal
     * against stored signals of equal type and value, groups matches by owning
     * sanction, sums the stored weights per group, and returns the
     * highest-scoring group. Only active, non-stayed, unexpired suspensions and
     * custodies are considered. An empty probe yields no match.
     *
     * @param now    current timestamp in epoch milliseconds
     * @param types  probe signal type codes
     * @param values probe signal values, parallel to {@code types}
     * @return a stage yielding the best match, or null when none is found
     */
    CompletableFuture<EvasionMatch> scoreEvasion(long now, int[] types, String[] values);

    /**
     * Resolves the active behavioural constraints for a player.
     *
     * <p>Returns one row per active, non-stayed, unexpired sanction carrying a
     * non-zero behavioural mask, regardless of measure. Connection-blocking and
     * non-blocking sanctions alike may contribute, since concealment applies to
     * permitted shadowed connections.
     *
     * @param uuid target UUID string
     * @param now  current timestamp in epoch milliseconds
     * @return a stage yielding the contributing rows; empty when none apply
     */
    CompletableFuture<java.util.List<BehaviourRow>> activeBehaviours(String uuid, long now);

    /**
     * Resolves the verbose-report preference for a staff member.
     *
     * @param uuid staff UUID string
     * @return a stage yielding the preference; defaults to {@code true} when no
     *         row is recorded
     */
    CompletableFuture<Boolean> getVerbose(String uuid);

    /**
     * Persists the verbose-report preference for a staff member.
     *
     * @param uuid    staff UUID string
     * @param verbose the preference to store
     * @return a stage completing when the preference is persisted
     */
    CompletableFuture<Void> setVerbose(String uuid, boolean verbose);

    /**
     * Resolves a sanction by identifier.
     *
     * @param id the sanction identifier
     * @return a stage yielding the sanction view, or null when no such sanction
     *         exists
     */
    CompletableFuture<SanctionView> findSanction(long id);


    /**
     * Lists a player's sanctions in reverse-chronological order, paginated.
     *
     * @param uuid   target UUID string
     * @param limit  maximum rows to return
     * @param offset rows to skip
     * @return a stage yielding the page of sanction views
     */
    CompletableFuture<java.util.List<SanctionView>> listSanctions(String uuid, int limit, int offset);

    /**
     * Counts a player's total recorded sanctions.
     *
     * @param uuid target UUID string
     * @return a stage yielding the total count
     */
    CompletableFuture<Integer> countSanctions(String uuid);

    /**
     * Resolves precedent counts across a joinder chain, disregarding decayed and
     * lifted sanctions. The chain root is located by walking parent links up from
     * {@code nodeId}; every sanction in the root's subtree contributes.
     *
     * @param nodeId a member of the chain (typically the weave parent)
     * @param now    current timestamp in epoch milliseconds
     * @return a stage yielding the chain precedent counts
     */
    CompletableFuture<Precedent> chainPrecedent(long nodeId, long now);

    /**
     * Amends an existing sanction, updating only the supplied fields, replacing
     * its cited provisions when {@code counts} is non-empty, and appending an
     * audit entry recording the difference. All within a single transaction.
     *
     * @param id        the sanction identifier
     * @param rule      new primary rule, or null to retain
     * @param reason    new reason, or null to retain
     * @param expiresAt new expiry timestamp, or null to retain
     * @param counts    replacement cited provisions; empty leaves them untouched
     * @param actor     the amending actor's name
     * @param now       amend timestamp in epoch milliseconds
     * @return a stage yielding the amend outcome
     */
    CompletableFuture<AmendResult> amendSanction(long id, String rule, String reason,
                                                 Long expiresAt, String[] counts,
                                                 String actor, long now);

    /**
     * Submits an appeal against a sanction, rejecting a duplicate while one is
     * pending.
     *
     * @param banId     the appealed sanction identifier
     * @param appellant the submitting player's name
     * @param text      the appeal body
     * @param now       submission timestamp in epoch milliseconds
     * @return a stage yielding the new appeal identifier, or a non-positive value
     *         when a pending appeal already exists
     */
    CompletableFuture<Long> submitAppeal(long banId, String appellant, String text, long now);

    /**
     * Counts appeals currently awaiting review.
     *
     * @return a stage yielding the pending-appeal count
     */
    CompletableFuture<Integer> countPendingAppeals();

    /**
     * Lists appeals awaiting review in submission order, paginated.
     *
     * @param limit  maximum rows to return
     * @param offset rows to skip
     * @return a stage yielding the page of pending appeals
     */
    CompletableFuture<java.util.List<AppealView>> listPendingAppeals(int limit, int offset);

    /**
     * Resolves an appeal by identifier.
     *
     * @param id the appeal identifier
     * @return a stage yielding the appeal, or null when absent
     */
    CompletableFuture<AppealView> findAppeal(long id);

    /**
     * Records a verdict against a pending appeal.
     *
     * @param id       the appeal identifier
     * @param status   the terminal status ({@code ACCEPTED} or {@code DENIED})
     * @param verdict  optional verdict reason, or null
     * @param reviewer the deciding actor's name
     * @param now      decision timestamp in epoch milliseconds
     * @return a stage yielding the number of rows decided (zero or one)
     */
    CompletableFuture<Integer> decideAppeal(long id, String status, String verdict,
                                            String reviewer, long now);

    /**
     * Appends a free-form audit entry.
     *
     * @param banId  the associated sanction identifier, or null when none
     * @param action the audit action code
     * @param actor  the acting authority
     * @param ts     the entry timestamp in epoch milliseconds
     * @param detail free-form detail, or null
     * @return a stage completing when the entry is persisted
     */
    CompletableFuture<Void> recordAudit(Long banId, String action, String actor, long ts, String detail);

    /**
     * Lists sanctions issued by a staff member, most recent first.
     *
     * @param staff  staff name
     * @param limit  maximum rows
     * @param offset rows to skip
     * @return a stage yielding the page of sanctions
     */
    CompletableFuture<java.util.List<SanctionView>> listByStaff(String staff, int limit, int offset);

    /**
     * Lists sanctions citing a rule as their primary rule, most recent first.
     *
     * @param rule   rule identifier
     * @param limit  maximum rows
     * @param offset rows to skip
     * @return a stage yielding the page of sanctions
     */
    CompletableFuture<java.util.List<SanctionView>> listByRule(String rule, int limit, int offset);

    /**
     * Lists the fingerprint signals attached to a sanction.
     *
     * @param banId the sanction identifier
     * @return a stage yielding the signals
     */
    CompletableFuture<java.util.List<SignalRow>> listSignals(long banId);

    /**
     * Lists the additional cited provisions of a sanction.
     *
     * @param banId the sanction identifier
     * @return a stage yielding the cited rule identifiers
     */
    CompletableFuture<java.util.List<String>> listProvisions(long banId);

    /**
     * Lists audit entries, optionally filtered to a single sanction, most recent
     * first.
     *
     * @param banId  the sanction identifier, or null for the global log
     * @param limit  maximum rows
     * @param offset rows to skip
     * @return a stage yielding the audit entries
     */
    CompletableFuture<java.util.List<AuditRow>> listAudit(Long banId, int limit, int offset);

    /**
     * Returns every sanction in the joinder chain containing a node, ordered by
     * identifier. The chain root is located by walking parent links up from the
     * supplied node.
     *
     * @param anyId a member of the chain
     * @return a stage yielding the chain members
     */
    CompletableFuture<java.util.List<SanctionView>> chainNodes(long anyId);

    /**
     * Computes aggregate system counters.
     *
     * @return a stage yielding the counters
     */
    CompletableFuture<StatsRow> stats();

    /**
     * Lists sanctions across all subjects, most recent first.
     *
     * @param limit  maximum rows
     * @param offset rows to skip
     * @return a stage yielding the page of sanctions
     */
    CompletableFuture<java.util.List<SanctionView>> listRecent(int limit, int offset);

    /**
     * Lists the distinct subjects that have borne a sanction, most recently
     * active first.
     *
     * @param limit  maximum rows
     * @param offset rows to skip
     * @return a stage yielding the subject UUID strings
     */
    CompletableFuture<java.util.List<String>> distinctSubjects(int limit, int offset);

    /**
     * Lists appeals of every status, most recent first.
     *
     * @param limit  maximum rows
     * @param offset rows to skip
     * @return a stage yielding the appeals
     */
    CompletableFuture<java.util.List<AppealView>> listAppeals(int limit, int offset);

    /**
     * Lists audit entries bearing a given action, most recent first.
     *
     * @param action the action code
     * @param limit  maximum rows
     * @param offset rows to skip
     * @return a stage yielding the audit entries
     */
    CompletableFuture<java.util.List<AuditRow>> listAuditByAction(String action, int limit, int offset);

    /**
     * Persists a chat transcript against a sanction.
     *
     * <p>The two arrays are parallel; element {@code i} of each describes one
     * line. An empty batch completes successfully without a write.
     *
     * @param banId  the owning sanction identifier
     * @param stamps line timestamps in epoch milliseconds, ascending
     * @param bodies line bodies, parallel to {@code stamps}
     * @return a stage completing when the batch is persisted
     */
    CompletableFuture<Void> persistTranscript(long banId, long[] stamps, String[] bodies);

    /**
     * Lists the persisted chat transcript for a sanction, in ascending order.
     *
     * @param banId the sanction identifier
     * @return a stage yielding the transcript rows; empty when none exist
     */
    CompletableFuture<java.util.List<ChatTranscriptRow>> listTranscript(long banId);

    /**
     * Persists the decision trace of a sanction, replacing any existing trace
     * for the same sanction.
     *
     * @param row the trace row
     * @return a stage completing when the trace is persisted
     */
    CompletableFuture<Void> persistTrace(SanctionTraceRow row);

    /**
     * Resolves the decision trace of a sanction.
     *
     * @param banId the sanction identifier
     * @return a stage yielding the trace, or null when none was recorded
     */
    CompletableFuture<SanctionTraceRow> findTrace(long banId);

    /**
     * Lists distinct signal values recorded for a signal type, ascending.
     *
     * @param type   the signal type code
     * @param limit  maximum rows
     * @param offset rows to skip
     * @return a stage yielding the distinct values
     */
    CompletableFuture<java.util.List<String>> distinctSignalValues(int type, int limit, int offset);

    /**
     * Lists the sanctions whose captured signals include a given type and value,
     * most recent first.
     *
     * @param type   the signal type code
     * @param value  the signal value
     * @param limit  maximum rows
     * @param offset rows to skip
     * @return a stage yielding the matching sanctions
     */
    CompletableFuture<java.util.List<SanctionView>> sanctionsForSignalValue(int type, String value,
                                                                            int limit, int offset);

    /**
     * Lists the distinct accounts whose captured signals include a given type
     * and value, ascending by UUID.
     *
     * @param type  the signal type code
     * @param value the signal value
     * @return a stage yielding the matching account UUIDs
     */
    CompletableFuture<java.util.List<String>> accountsForSignalValue(int type, String value);

    /**
     * Lists the distinct accounts that share at least one captured signal with a
     * given account, excluding the account itself.
     *
     * @param uuid the account UUID
     * @return a stage yielding coincident account UUIDs, ascending
     */
    CompletableFuture<java.util.List<String>> coincidentAccounts(String uuid);

    /**
     * Streams signal-to-account edges in ascending {@code (type, value)} order
     * for cluster computation, bounded by a row cap.
     *
     * @param limit maximum rows
     * @return a stage yielding the edge rows
     */
    CompletableFuture<java.util.List<SignalLinkRow>> listSignalLinks(int limit);

    /**
     * Records a username observation for an account, retaining prior names as
     * history and refreshing the last-seen timestamp for the observed name.
     *
     * @param uuid the account UUID string
     * @param name the observed name
     * @param now  the observation timestamp in epoch milliseconds
     * @return a stage completing when the observation is persisted
     */
    CompletableFuture<Void> recordUsername(String uuid, String name, long now);

    /**
     * Resolves the most recent known name for an account, consulting the
     * username history first and the sanction subject-name column as a fallback.
     *
     * @param uuid the account UUID string
     * @return a stage yielding the name, or null when unknown
     */
    CompletableFuture<String> resolveName(String uuid);

    /**
     * Resolves the most recently seen account for a name, case-insensitively,
     * consulting the username history first and the sanction subject-name column
     * as a fallback.
     *
     * @param name the name
     * @return a stage yielding the account UUID string, or null when unknown
     */
    CompletableFuture<String> resolveUuidByName(String name);

    /**
     * Loads a bounded name index for cache warming, combining sanction
     * subject-name entries with username-history entries; the latter take
     * precedence on collision.
     *
     * @param limit maximum rows per source
     * @return a stage yielding an account-to-name map
     */
    CompletableFuture<java.util.Map<String, String>> warmNames(int limit);

    /**
     * Rebuilds the signal-frequency aggregate and the corpus-size statistic.
     *
     * <p>The aggregate is recomputed as the count of distinct accounts bearing
     * each recorded signal value, joining {@code signals} to {@code bans} on
     * sanction identity. The corpus-size statistic is recomputed as the count of
     * distinct sanctioned accounts. The rebuild executes as a single transaction;
     * on failure it rolls back and the previous aggregate remains in effect.
     *
     * @return a stage completing when the rebuild finishes
     */
    CompletableFuture<Void> refreshFrequencyAggregate();

    /**
     * Loads the signal-frequency aggregate for in-memory snapshotting.
     *
     * @param limit maximum rows to return
     * @return a stage yielding the aggregate rows
     */
    CompletableFuture<java.util.List<SignalFreqRow>> loadFrequencies(int limit);

    /**
     * Returns the recorded corpus size, the number of distinct sanctioned
     * accounts as of the last aggregate rebuild.
     *
     * @return a stage yielding the corpus size, at least one
     */
    CompletableFuture<Long> corpusSize();

    /**
     * Opens a session interval for an account at login.
     *
     * @param uuid the account UUID string
     * @param ip   the remote address string, or null when unknown
     * @param at   the login timestamp in epoch milliseconds
     * @return a stage yielding the generated interval identifier
     */
    CompletableFuture<Long> openSessionInterval(String uuid, String ip, long at);

    /**
     * Closes every open session interval for an account at logout.
     *
     * @param uuid the account UUID string
     * @param at   the logout timestamp in epoch milliseconds
     * @return a stage yielding the number of intervals closed
     */
    CompletableFuture<Integer> closeSessionIntervals(String uuid, long at);

    /**
     * Lists an account's session intervals, most recent first.
     *
     * @param uuid  the account UUID string
     * @param limit maximum rows to return
     * @return a stage yielding the intervals
     */
    CompletableFuture<java.util.List<SessionIntervalRow>> listSessionIntervals(String uuid, int limit);

    /**
     * Records one activity observation for an account in its hour-of-day bin,
     * incrementing the bin's hit count.
     *
     * @param uuid the account UUID string
     * @param hour the local hour of day, in {@code [0, 23]}
     * @return a stage completing when the observation is persisted
     */
    CompletableFuture<Void> recordActivityHour(String uuid, int hour);

    /**
     * Returns an account's 24-bin hour-of-day activity histogram.
     *
     * @param uuid the account UUID string
     * @return a stage yielding a length-24 array of hit counts, indexed by hour
     */
    CompletableFuture<long[]> hourHistogram(String uuid);

    /**
     * Loads every persisted signal-reliability belief.
     *
     * @return a stage yielding the belief rows; absent types default to their
     *         catalogue priors at model construction
     */
    CompletableFuture<java.util.List<BeliefRow>> loadBeliefs();

    /**
     * Persists a signal-reliability belief, replacing any prior belief for the
     * same type.
     *
     * @param type  the signal type code
     * @param alpha the positive Beta alpha parameter
     * @param beta  the positive Beta beta parameter
     * @param now   the update timestamp in epoch milliseconds
     * @return a stage completing when the belief is persisted
     */
    CompletableFuture<Void> saveBelief(int type, double alpha, double beta, long now);

    /**
     * Returns the probe-to-account agreements against active connection-blocking
     * sanctions.
     *
     * <p>The two arrays are parallel and describe the probe signals: for probe
     * signal {@code i}, {@code types[i]} and {@code values[i]} together identify a
     * value to match. The query returns, for each stored signal of an active,
     * non-stayed, unexpired suspension or custody whose type and value equal a
     * probe signal, the owning account UUID, the matched type and value, and the
     * sanction issue time as the observation timestamp. An empty probe yields an
     * empty result.
     *
     * @param types  probe signal type codes
     * @param values probe signal values, parallel to {@code types}
     * @param now    current timestamp in epoch milliseconds for expiry filtering
     * @return a stage yielding the agreement rows
     */
    CompletableFuture<java.util.List<org.synergyst.minutiae.fingerprint.MatchingSignalRow>>
    matchingSignals(int[] types, String[] values, long now);

    /**
     * Publishes a cross-server directive.
     *
     * @param origin  publishing server identifier
     * @param target  addressed server identifier, or null for broadcast
     * @param kind    directive kind name
     * @param subject subject account UUID string, or empty
     * @param payload kind-specific payload, or empty
     * @param now     publication timestamp in epoch milliseconds
     * @param ttlMs   directive lifetime in milliseconds
     * @return a stage yielding the generated directive identifier
     */
    CompletableFuture<Long> publishDirective(String origin, String target, String kind,
                                             String subject, String payload,
                                             long now, long ttlMs);

    /**
     * Polls unexpired directives addressed to an instance above a cursor.
     *
     * <p>A broadcast row (null target) is addressed to every instance except
     * its origin; a targeted row only to its target. Rows are returned in
     * ascending identifier order, bounded by the limit.
     *
     * @param serverId the polling instance identifier
     * @param afterId  the exclusive lower identifier bound
     * @param now      current timestamp for expiry filtering
     * @param limit    maximum rows to return
     * @return a stage yielding the directive batch
     */
    CompletableFuture<java.util.List<DirectiveRow>> pollDirectives(String serverId, long afterId,
                                                                   long now, int limit);

    /**
     * Returns the persisted directive cursor of an instance.
     *
     * @param serverId the instance identifier
     * @return a stage yielding the cursor, or {@code -1} when none is stored
     */
    CompletableFuture<Long> directiveCursor(String serverId);

    /**
     * Persists the directive cursor of an instance.
     *
     * @param serverId the instance identifier
     * @param lastId   the highest consumed directive identifier
     * @return a stage completing when the cursor is persisted
     */
    CompletableFuture<Void> saveDirectiveCursor(String serverId, long lastId);

    /**
     * Returns the highest directive identifier, or zero on an empty table.
     *
     * @return a stage yielding the maximum identifier
     */
    CompletableFuture<Long> maxDirectiveId();

    /**
     * Deletes expired directives.
     *
     * @param now current timestamp in epoch milliseconds
     * @return a stage yielding the number of rows purged
     */
    CompletableFuture<Integer> purgeDirectives(long now);

    /**
     * Activates suspended sentences of a player under a rule whose recidivism
     * window has not elapsed, transitioning them from recorded to active.
     *
     * @param uuid target UUID string
     * @param rule the rule of the newly issued sanction, or null for none
     * @param now  current timestamp in epoch milliseconds
     * @return a stage yielding the number of sanctions activated
     */
    CompletableFuture<Integer> activateSuspended(String uuid, String rule, long now);

    /**
     * Appends an internal case note.
     *
     * @param banId  the associated sanction identifier, or null
     * @param uuid   the subject UUID string, or null
     * @param author the authoring staff name
     * @param text   the note body
     * @param ts     the note timestamp in epoch milliseconds
     * @return a stage yielding the generated note identifier
     */
    CompletableFuture<Long> addCaseNote(Long banId, String uuid, String author,
                                        String text, long ts);

    /**
     * Claims sanctions whose scheduled review is due: selects and clears the
     * review mark within one transaction, so a claimed sanction is never
     * re-claimed by a concurrent or subsequent poll.
     *
     * @param now   current timestamp in epoch milliseconds
     * @param limit maximum rows to claim
     * @return a stage yielding the claimed sanctions
     */
    CompletableFuture<java.util.List<SanctionView>> claimReviewDue(long now, int limit);

    /**
     * Criterion column of a bulk lift. The set is closed; the executing
     * backend maps each constant to a fixed column name, so criterion
     * selection can never inject SQL.
     */
    enum BulkCriterion {

        /** Match on the primary rule identifier. */
        RULE,

        /** Match on the attributed staff name. */
        STAFF
    }

    /**
     * Lifts a sanction, recording the lifting actor, reason, and kind, and
     * appending an audit entry, all within a single transaction.
     *
     * <p>The update is guarded so that only a not-yet-lifted sanction is
     * affected: it clears the active and stayed flags and stamps the lift
     * metadata. The returned count is one when the sanction was lifted by this
     * call and zero when it was already lifted or absent.
     *
     * <p>The kind code selects the precedent semantics applied by
     * {@link #precedent} and {@link #chainPrecedent}: a vacated sanction (code
     * zero) is excluded from precedent, while a pardoned or time-served
     * sanction remains counted. A positive {@code probationUntil} additionally
     * stamps a probation window onto the lifted record; callers pass zero to
     * leave any existing window untouched.
     *
     * @param id             the sanction identifier
     * @param actor          the lifting actor's name
     * @param at             lift timestamp in epoch milliseconds
     * @param reason         optional lift reason, or null
     * @param liftKind       the lift kind code as defined by
     *                       {@code LiftKind.code()}
     * @param probationUntil probation-window expiry in epoch milliseconds, or
     *                       zero for none
     * @return a stage yielding the number of rows lifted (zero or one)
     */
    CompletableFuture<Integer> liftSanction(long id, String actor, long at,
                                            String reason, int liftKind, long probationUntil);

    /**
     * Counts the sanctions a bulk lift would affect: unlifted rows matching
     * the criterion that are active, stayed, or suspended.
     *
     * @param criterion the criterion column
     * @param value     the matched value
     * @return a stage yielding the affected count
     */
    CompletableFuture<Integer> countLiftable(BulkCriterion criterion, String value);

    /**
     * Lifts every sanction matching a criterion in one transaction, always
     * under the vacate kind, and appends a single aggregate audit entry.
     *
     * <p>The predicate mirrors {@link #countLiftable}: unlifted rows that are
     * active, stayed, or suspended. Suspended sentences are cleared alongside
     * the active and stayed flags, since an amnesty covers pending activation
     * as much as live effect.
     *
     * @param criterion the criterion column
     * @param value     the matched value
     * @param actor     the lifting actor's name
     * @param at        lift timestamp in epoch milliseconds
     * @param reason    optional lift reason, or null
     * @return a stage yielding the number of rows lifted
     */
    CompletableFuture<Integer> bulkLift(BulkCriterion criterion, String value,
                                        String actor, long at, String reason);

    /**
     * Reverses an erroneous lift, restoring the sanction's standing and
     * appending an audit entry, all within a single transaction.
     *
     * <p>The update is guarded so that only a lifted sanction is affected. The
     * lift metadata is cleared and the kind reset to zero. The active flag is
     * recomputed from present state: an instantaneous measure stays inactive,
     * a temporal sanction whose expiry has passed stays inactive, and every
     * other sanction returns to active. A sanction that was stayed or
     * suspended at lift time returns as plainly active, since its deferral
     * window has been consumed by the lift-unlift round trip; this
     * approximation is deliberate and recorded in the audit detail.
     *
     * @param id     the sanction identifier
     * @param actor  the reinstating actor's name
     * @param at     unlift timestamp in epoch milliseconds
     * @param reason optional reinstatement reason, or null
     * @return a stage yielding the number of rows reinstated (zero or one)
     */
    CompletableFuture<Integer> unliftSanction(long id, String actor, long at, String reason);

}