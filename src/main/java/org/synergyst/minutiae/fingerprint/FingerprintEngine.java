package org.synergyst.minutiae.fingerprint;

import org.synergyst.minutiae.async.AsyncScheduler;
import org.synergyst.minutiae.lifecycle.LifecycleComponent;
import org.synergyst.minutiae.lifecycle.Reloadable;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.storage.BeliefRow;
import org.synergyst.minutiae.storage.Storage;
import org.synergyst.minutiae.web.hive.ClusterConfig;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Central coordinator of the Bayesian fingerprint model, its learned reliability
 * beliefs, and the collaborators that supply its inputs.
 *
 * <p>The engine holds a single published {@link EvidenceModel} built from the
 * per-signal-type reliability beliefs, the value-frequency oracle that estimates
 * the non-match likelihood, the network classifier that labels remote addresses,
 * and the session-correlation configuration that governs the behavioural log-odds
 * adjustment. It exposes three operations: scoring an incoming login against
 * stored sanctioned accounts, assessing a single named account for diagnostic
 * display, and adjudicating a flag to update a signal type's belief.
 *
 * <h2>Belief lifecycle</h2>
 *
 * <p>At boot the engine loads every persisted {@link BeliefRow} and constructs the
 * model, substituting a persisted belief for its signal type's catalogue prior
 * where present. On adjudication the belief of each signal type that participated
 * in the flagged agreement is reinforced or penalised by one observation, the
 * updated belief is persisted, and the model is rebuilt. Belief updates are rare
 * relative to scoring, so a full model rebuild per update is acceptable; scoring
 * observes the model through a single volatile reference and never blocks on a
 * rebuild in progress.
 *
 * <h2>Frequency snapshot lifecycle</h2>
 *
 * <p>The frequency oracle serves collision statistics from an in-memory snapshot
 * rebuilt off the main thread. The engine triggers a rebuild at boot and on
 * request from the maintenance sweep, dispatching the blocking aggregate refresh
 * and reload to the asynchronous scheduler.
 *
 * <h2>Threading</h2>
 *
 * <p>Scoring and assessment are pure reads over the published model and the oracle
 * snapshot and are safe from any thread. Belief adjudication and snapshot rebuild
 * mutate published references by wholesale assignment and dispatch their I/O to
 * the asynchronous scheduler. The engine performs no blocking storage call on the
 * server main thread outside its boot method.
 */
public final class FingerprintEngine implements LifecycleComponent, Reloadable {

    private final KernelLogger log;
    private final Storage storage;
    private final AsyncScheduler scheduler;
    private final EvidenceConfig evidenceConfig;
    private final SessionCorrelationConfig correlationConfig;
    private final ClusterConfig clusterConfig;
    private final NetworkClassifier networkClassifier;
    private final boolean reverseDns;

    private final StorageFrequencyOracle oracle;
    private final SessionCorrelation correlation;

    // The model is republished by wholesale assignment on rebuild; scoring reads
    // it once per call, so a rebuild never yields a half-updated model.
    private volatile EvidenceModel model;

    public FingerprintEngine(final KernelLogger log,
                             final Storage storage,
                             final AsyncScheduler scheduler,
                             final EvidenceConfig evidenceConfig,
                             final SessionCorrelationConfig correlationConfig,
                             final ClusterConfig clusterConfig,
                             final NetworkClassifier networkClassifier,
                             final boolean reverseDns) {
        this.log = log;
        this.storage = storage;
        this.scheduler = scheduler;
        this.evidenceConfig = evidenceConfig;
        this.correlationConfig = correlationConfig;
        this.clusterConfig = clusterConfig;
        this.networkClassifier = networkClassifier;
        this.reverseDns = reverseDns;
        this.oracle = new StorageFrequencyOracle(log, storage);
        this.correlation = new SessionCorrelation(correlationConfig);
    }

    @Override
    public String tag() {
        return "fingerprint-engine";
    }

    @Override
    public void boot() {
        final int loaded = rebuildModel();
        oracle.rebuild(System.currentTimeMillis());
        log.info("fingerprint", "engine online: prior=%.3f bits, flag>=%.2f, %d belief(s) loaded",
                model.priorBits(), model.flagThreshold(), loaded);
    }

    @Override
    public String reloadTag() {
        return "fingerprint-engine";
    }

    @Override
    public void reload() {
        rebuildModel();
        log.info("fingerprint", "engine model recompiled from persisted beliefs");
    }

    @Override
    public void shutdown() {
        // No owned resources beyond published references; nothing to release.
    }

    /**
     * Rebuilds the evidence model from the persisted belief table, using each
     * signal type's catalogue prior where no belief is stored.
     *
     * @return the number of persisted beliefs applied over their priors
     */
    private int rebuildModel() {
        final SignalType[] types = SignalType.values();
        final BetaBelief[] beliefs = new BetaBelief[types.length];
        for (int t = 0; t < types.length; t++) {
            beliefs[t] = types[t].reliabilityPrior();
        }
        int loaded = 0;
        for (final BeliefRow row : storage.loadBeliefs().join()) {
            if (row.type() >= 0 && row.type() < beliefs.length) {
                beliefs[row.type()] = new BetaBelief(row.alpha(), row.beta());
                loaded++;
            }
        }
        this.model = EvidenceModel.build(evidenceConfig, beliefs);
        return loaded;
    }

    /**
     * Triggers an asynchronous rebuild of the frequency snapshot.
     *
     * @param now the rebuild timestamp in epoch milliseconds
     */
    public void refreshFrequencies(final long now) {
        scheduler.run(() -> oracle.rebuild(now));
    }

    /**
     * Scores a set of probe-to-account agreements and returns the best-matching
     * account, adjusted by behavioural session correlation.
     *
     * <p>The rows are grouped by account and each group is scored under the
     * evidence model. The best-scoring candidate is then correlated against
     * the probe subject's temporal activity: session-interval overlap
     * contributes negative evidence, shared-address handoffs positive, and
     * active-hour similarity weak positive, all bounded by the correlation
     * configuration. The adjustment loads both accounts' intervals and
     * hour histograms from storage and therefore blocks the calling thread;
     * callers run on the asynchronous pre-login thread or a scheduler
     * thread by contract. Any correlation failure degrades to the unadjusted
     * field-agreement score.
     *
     * @param rows         the probe-to-account agreements, in any order
     * @param probeSubject the connecting account, or null when unknown
     * @param now          the scoring time in epoch milliseconds
     * @return the best-matching account and its evidence, or null when no
     *         agreement exists
     */
    public EvasionAssessment assessLogin(final List<MatchingSignalRow> rows,
                                         final java.util.UUID probeSubject, final long now) {
        if (rows.isEmpty()) {
            return null;
        }
        final Map<String, List<MatchingSignalRow>> byAccount = new TreeMap<>();
        for (final MatchingSignalRow r : rows) {
            byAccount.computeIfAbsent(r.uuid(), k -> new java.util.ArrayList<>()).add(r);
        }
        EvasionAssessment best = null;
        for (final Map.Entry<String, List<MatchingSignalRow>> e : byAccount.entrySet()) {
            final MatchEvidence evidence = scoreGroup(e.getValue(), now, false);
            if (best == null || evidence.probability() > best.evidence().probability()) {
                best = new EvasionAssessment(e.getKey(), evidence);
            }
        }
        if (best == null || probeSubject == null) {
            return best;
        }
        final CorrelationScore score =
                correlateAccounts(probeSubject.toString(), best.uuid(), now);
        if (score == CorrelationScore.NEUTRAL) {
            return best;
        }
        return new EvasionAssessment(best.uuid(), best.evidence().adjusted(score));
    }

    /**
     * Correlates the temporal activity of two accounts.
     *
     * <p>Both accounts' session intervals (bounded by the configured limit)
     * and hour-of-day histograms are loaded; an account with no recorded
     * interval yields the neutral score, as does any storage failure.
     *
     * @param probeUuid     the probe account UUID string
     * @param candidateUuid the candidate account UUID string
     * @param now           the reference time in epoch milliseconds
     * @return the correlation score, never null
     */
    private CorrelationScore correlateAccounts(final String probeUuid,
                                               final String candidateUuid, final long now) {
        if (probeUuid.equals(candidateUuid)) {
            return CorrelationScore.NEUTRAL;
        }
        try {
            final int limit = correlationConfig.intervalLimit();
            final List<org.synergyst.minutiae.storage.SessionIntervalRow> a =
                    storage.listSessionIntervals(probeUuid, limit).join();
            final List<org.synergyst.minutiae.storage.SessionIntervalRow> b =
                    storage.listSessionIntervals(candidateUuid, limit).join();
            if (a.isEmpty() || b.isEmpty()) {
                return CorrelationScore.NEUTRAL;
            }
            final long[] histA = storage.hourHistogram(probeUuid).join();
            final long[] histB = storage.hourHistogram(candidateUuid).join();
            return correlation.correlate(
                    loginsOf(a), logoutsOf(a), ipHashesOf(a), histA,
                    loginsOf(b), logoutsOf(b), ipHashesOf(b), histB, now);
        } catch (final RuntimeException e) {
            log.warn("fingerprint", "session correlation failed for %s vs %s: %s",
                    probeUuid, candidateUuid, e.getMessage());
            return CorrelationScore.NEUTRAL;
        }
    }

    private static long[] loginsOf(
            final List<org.synergyst.minutiae.storage.SessionIntervalRow> rows) {
        final long[] out = new long[rows.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = rows.get(i).loginAt();
        }
        return out;
    }

    private static long[] logoutsOf(
            final List<org.synergyst.minutiae.storage.SessionIntervalRow> rows) {
        final long[] out = new long[rows.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = rows.get(i).logoutAt();
        }
        return out;
    }

    private static long[] ipHashesOf(
            final List<org.synergyst.minutiae.storage.SessionIntervalRow> rows) {
        final long[] out = new long[rows.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = SessionCorrelation.ipHash(rows.get(i).ip());
        }
        return out;
    }

    /**
     * Assesses a single account's agreements with a probe, retaining the full
     * per-signal decomposition for display.
     *
     * @param rows the probe-to-account agreements for one account
     * @param now  the scoring time in epoch milliseconds
     * @return the calibrated evidence with contributions populated
     */
    public MatchEvidence assessAccount(final List<MatchingSignalRow> rows, final long now) {
        return scoreGroup(rows, now, true);
    }

    /**
     * Scores one account's agreement group under the evidence model.
     *
     * @param group   the agreements for a single account
     * @param now     the scoring time
     * @param explain whether to populate the contribution decomposition
     * @return the calibrated evidence
     */
    private MatchEvidence scoreGroup(final List<MatchingSignalRow> group, final long now,
                                     final boolean explain) {
        final int k = group.size();
        // Marshal the group into the model's structure-of-arrays contract.
        final int[] types = new int[k];
        final String[] values = new String[k];
        final long[] counts = new long[k];
        final long[] observed = new long[k];
        for (int i = 0; i < k; i++) {
            final MatchingSignalRow r = group.get(i);
            types[i] = r.type();
            values[i] = r.value();
            // Per-value distinct-account count drives the non-match likelihood.
            counts[i] = oracle.accountsBearing(r.type(), r.value());
            observed[i] = r.observedAt();
        }
        return model.score(k, types, values, counts, observed,
                oracle.totalAccounts(), now, explain);
    }

    /**
     * Updates the reliability beliefs of the signal types that composed a flag
     * according to a moderator's verdict, persists them, and rebuilds the model.
     *
     * <p>The load, per-type update, and rebuild are dispatched to the
     * asynchronous scheduler so that no caller thread blocks on storage.
     *
     * @param types     the signal type codes that agreed in the flagged match
     * @param confirmed whether the moderator confirmed the flag as true evasion
     * @return a stage completing when the updated model is published
     */
    public java.util.concurrent.CompletableFuture<Void> adjudicate(final int[] types,
                                                                   final boolean confirmed) {
        return scheduler.run(() -> {
            final long now = System.currentTimeMillis();
            final SignalType[] all = SignalType.values();
            final Map<Integer, BetaBelief> current = new java.util.HashMap<>();
            for (final BeliefRow row : storage.loadBeliefs().join()) {
                current.put(row.type(), new BetaBelief(row.alpha(), row.beta()));
            }
            for (final int t : types) {
                if (t < 0 || t >= all.length) {
                    continue;
                }
                final BetaBelief base = current.getOrDefault(t, all[t].reliabilityPrior());
                final BetaBelief updated = confirmed ? base.reinforce(1.0d) : base.penalize(1.0d);
                storage.saveBelief(t, updated.alpha(), updated.beta(), now).join();
            }
            rebuildModel();
            log.info("fingerprint", "adjudicated %d signal type(s) as %s",
                    types.length, confirmed ? "match" : "non-match");
        });
    }

    /** Returns the published evidence model. */
    public EvidenceModel model() {
        return model;
    }

    /** Returns the frequency oracle. */
    public StorageFrequencyOracle oracle() {
        return oracle;
    }

    /** Returns the session-correlation computation. */
    public SessionCorrelation correlation() {
        return correlation;
    }

    /** Returns the session-correlation configuration. */
    public SessionCorrelationConfig correlationConfig() {
        return correlationConfig;
    }

    /** Returns the network classifier. */
    public NetworkClassifier networkClassifier() {
        return networkClassifier;
    }

    /** Returns the cluster configuration. */
    public ClusterConfig clusterConfig() {
        return clusterConfig;
    }

    /** Reports whether reverse-DNS derivation is enabled. */
    public boolean reverseDnsEnabled() {
        return reverseDns;
    }
}