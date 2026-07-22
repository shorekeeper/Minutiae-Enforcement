package org.synergyst.minutiae.fingerprint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Calibrated probabilistic scorer for candidate identity matches.
 *
 * <p>The model implements the Fellegi–Sunter record-linkage rule, combining
 * the evidence of individually weak signals into a single calibrated posterior
 * probability of shared identity. It is immutable after construction and safe
 * for concurrent scoring; belief updates are effected by publishing a new
 * instance.
 *
 * <h2>Model</h2>
 *
 * <p>For each signal type {@code t} define the match and non-match likelihoods
 * of an agreement, {@code m_t = P(agree_t | M)} and
 * {@code u_t = P(agree_t | U)}. The posterior odds of a match are the prior
 * odds multiplied by the per-type likelihood ratios of the agreeing types; in
 * base-2 logarithms the product becomes a sum of additive weights in bits:
 * <pre>
 *   S = log2 O(M) + sum over contributing t of log2( m_t / u_t )
 *   P(M | evidence) = 1 / (1 + 2^(-S))
 * </pre>
 *
 * <h2>Hierarchical dependence gating</h2>
 *
 * <p>The independence assumption underlying the sum fails structurally for
 * signal types derived from one attribute: a full-address agreement entails
 * the subnet, provider, and reverse-DNS agreements, so their weights describe
 * one observation, not four. The model therefore gates agreements by the
 * dependence families declared on {@link SignalType}: within one family, only
 * the agreement of the most specific rank present contributes weight, and
 * every dominated agreement contributes exactly zero bits. Dominated
 * agreements remain in the explanation with a zero contribution, preserving
 * decomposition transparency. A coarser signal still contributes fully when
 * no more specific signal of its family agrees, which is precisely the case
 * (a changed address within a stable subnet) the coarser type exists for.
 *
 * <h2>Estimation of the non-match likelihood</h2>
 *
 * <p>The coincidental-agreement probability is estimated per value by the
 * inverse-document-frequency estimate {@code u_{t,v} = n_{t,v} / N}, where
 * {@code n_{t,v}} distinct accounts bear value {@code v} out of a corpus of
 * {@code N}. The estimate is clamped to {@code [1/max(N,2), 1 - eps]}.
 *
 * <h2>Reliability and conservative shrinkage</h2>
 *
 * <p>The reliability {@code m_t} is the lower confidence estimate
 * {@code clamp(E[m_t] - z * sd(m_t), eps, 1 - eps)} of a {@link BetaBelief},
 * attenuating types whose reliability is not yet well-established.
 *
 * <h2>Temporal decay</h2>
 *
 * <p>Each agreement weight is scaled by {@code d(a) = exp(-lambda_t * a)} of
 * the observation age {@code a}, with {@code lambda_t = ln 2 / H_t} and
 * {@code H_t} the type's half-life; a half-life of zero disables decay.
 *
 * <h2>Bounding</h2>
 *
 * <p>Each per-signal weight is clamped to
 * {@code [-weightCapBits, weightCapBits]} before decay, so no single
 * agreement can dominate the aggregate.
 *
 * <h2>Complexity</h2>
 *
 * <p>Per-type logarithms, family ordinals, and ranks are precomputed at
 * construction. Scoring performs two linear passes over the agreements (one
 * to resolve family domination, one to accumulate), with one logarithm, one
 * exponential, and a constant number of arithmetic operations per signal, and
 * no allocation on the numeric path beyond the optional explanation list.
 */
public final class EvidenceModel {

    private static final double LN2 = 0.6931471805599453d;
    private static final double INV_LN2 = 1.0d / LN2;
    private static final double M_FLOOR = 1.0e-4;
    private static final double M_CEIL = 1.0d - 1.0e-4;

    private final double priorBits;
    private final double shrinkageZ;
    private final double weightCapBits;
    private final double flagThreshold;

    private final BetaBelief[] beliefs;
    private final double[] logMEff;
    private final double[] lambdaPerMillis;
    private final int[] familyOrd;
    private final int[] familyRank;
    private final int familyCount;

    private EvidenceModel(final double priorBits, final double shrinkageZ,
                          final double weightCapBits, final double flagThreshold,
                          final BetaBelief[] beliefs, final double[] logMEff,
                          final double[] lambdaPerMillis, final int[] familyOrd,
                          final int[] familyRank, final int familyCount) {
        this.priorBits = priorBits;
        this.shrinkageZ = shrinkageZ;
        this.weightCapBits = weightCapBits;
        this.flagThreshold = flagThreshold;
        this.beliefs = beliefs;
        this.logMEff = logMEff;
        this.lambdaPerMillis = lambdaPerMillis;
        this.familyOrd = familyOrd;
        this.familyRank = familyRank;
        this.familyCount = familyCount;
    }

    /**
     * Builds a model from configuration and a per-type reliability belief table.
     *
     * @param config  the evidence configuration
     * @param beliefs reliability beliefs indexed by {@link SignalType#code()};
     *                its length must equal the number of signal types
     * @return the constructed model
     * @throws IllegalArgumentException if the belief table length is wrong
     */
    public static EvidenceModel build(final EvidenceConfig config, final BetaBelief[] beliefs) {
        final SignalType[] types = SignalType.values();
        if (beliefs.length != types.length) {
            throw new IllegalArgumentException(
                    "belief table length " + beliefs.length + " != type count " + types.length);
        }
        final double pi = config.priorEvasionRate();
        final double priorBits = log2(pi / (1.0 - pi));

        final BetaBelief[] copy = beliefs.clone();
        final double[] logMEff = new double[types.length];
        final double[] lambda = new double[types.length];
        final int[] famOrd = new int[types.length];
        final int[] famRank = new int[types.length];
        for (int t = 0; t < types.length; t++) {
            final double mEff = clamp(copy[t].lowerConfidence(config.shrinkageZ()), M_FLOOR, M_CEIL);
            logMEff[t] = log2(mEff);
            final long half = types[t].halfLifeMillis();
            lambda[t] = half <= 0L ? 0.0d : LN2 / (double) half;
            famOrd[t] = types[t].family().ordinal();
            famRank[t] = types[t].familyRank();
        }
        return new EvidenceModel(priorBits, config.shrinkageZ(), config.weightCapBits(),
                config.flagThreshold(), copy, logMEff, lambda, famOrd, famRank,
                SignalType.Family.values().length);
    }

    /**
     * Builds a model whose reliability beliefs are the catalogue priors.
     *
     * @param config the evidence configuration
     * @return the constructed model
     */
    public static EvidenceModel fromDefaults(final EvidenceConfig config) {
        final SignalType[] types = SignalType.values();
        final BetaBelief[] beliefs = new BetaBelief[types.length];
        for (int t = 0; t < types.length; t++) {
            beliefs[t] = types[t].reliabilityPrior();
        }
        return build(config, beliefs);
    }

    /**
     * Scores a candidate pair from its agreeing signals, supplied in
     * structure-of-arrays form.
     *
     * <p>The four arrays are parallel and describe {@code count} agreements.
     * For agreement {@code i}, {@code accountCounts[i]} is the number of
     * distinct accounts bearing {@code values[i]} of type {@code types[i]},
     * and {@code observedAt[i]} is the epoch-millisecond time of the stored
     * observation whose age is measured against {@code now}. Agreements
     * dominated within their dependence family contribute zero bits and are
     * retained in the explanation.
     *
     * @param count          number of agreements described by the arrays
     * @param types          signal type codes
     * @param values         signal values, for explanation
     * @param accountCounts  per-value distinct-account counts
     * @param observedAt     per-observation timestamps in epoch milliseconds
     * @param totalAccounts  corpus size {@code N}
     * @param now            scoring time in epoch milliseconds
     * @param explain        whether to populate the contribution list
     * @return the calibrated evidence
     */
    public MatchEvidence score(final int count, final int[] types, final String[] values,
                               final long[] accountCounts, final long[] observedAt,
                               final long totalAccounts, final long now, final boolean explain) {
        final double n = Math.max((double) totalAccounts, 2.0);
        final double uFloor = 1.0 / n;
        final double uCeil = 1.0 - 1.0e-9;

        // Pass one: the most specific agreeing rank per dependence family.
        final int[] bestRank = new int[familyCount];
        Arrays.fill(bestRank, Integer.MAX_VALUE);
        final int noneOrd = SignalType.Family.NONE.ordinal();
        for (int i = 0; i < count; i++) {
            final int fam = familyOrd[types[i]];
            if (fam != noneOrd && familyRank[types[i]] < bestRank[fam]) {
                bestRank[fam] = familyRank[types[i]];
            }
        }

        double s = priorBits;
        final List<EvidenceContribution> contribs =
                explain ? new ArrayList<>(count) : null;

        for (int i = 0; i < count; i++) {
            final int t = types[i];
            final int fam = familyOrd[t];
            final boolean dominated = fam != noneOrd && familyRank[t] > bestRank[fam];

            final double bearers = accountCounts[i] <= 0L ? 1.0 : (double) accountCounts[i];
            final double u = clamp(bearers / n, uFloor, uCeil);

            final long age = Math.max(0L, now - observedAt[i]);
            final double lambda = lambdaPerMillis[t];
            final double decay = lambda == 0.0d ? 1.0d : Math.exp(-lambda * (double) age);

            double contribution = 0.0d;
            if (!dominated) {
                double w = logMEff[t] - log2(u);
                if (w > weightCapBits) {
                    w = weightCapBits;
                } else if (w < -weightCapBits) {
                    w = -weightCapBits;
                }
                contribution = decay * w;
                s += contribution;
            }
            if (contribs != null) {
                contribs.add(new EvidenceContribution(t, values[i], contribution, u, age, decay));
            }
        }

        final double probability = 1.0 / (1.0 + Math.pow(2.0, -s));
        return new MatchEvidence(probability, s, priorBits,
                contribs == null ? List.of() : contribs, CorrelationScore.NEUTRAL);
    }

    /** Returns the prior log-odds in bits. */
    public double priorBits() {
        return priorBits;
    }

    /** Returns the posterior probability at which a candidate is flagged. */
    public double flagThreshold() {
        return flagThreshold;
    }

    /** Returns the shrinkage parameter in standard deviations. */
    public double shrinkageZ() {
        return shrinkageZ;
    }

    /**
     * Returns the reliability belief for a signal type code.
     *
     * @param type the signal type code
     * @return the belief in effect for the type
     */
    public BetaBelief beliefOf(final int type) {
        return beliefs[type];
    }

    private static double log2(final double x) {
        return Math.log(x) * INV_LN2;
    }

    private static double clamp(final double v, final double lo, final double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}