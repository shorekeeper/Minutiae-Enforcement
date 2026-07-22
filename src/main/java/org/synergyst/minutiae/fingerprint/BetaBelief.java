package org.synergyst.minutiae.fingerprint;

/**
 * Immutable Beta-distributed belief over a signal type's match reliability.
 *
 * <p>For a signal type {@code t}, its reliability is defined as
 * {@code m_t = P(the field agrees | the two accounts are the same entity)}.
 * This quantity is unknown a priori and is treated as a random variable with a
 * Beta prior, {@code m_t ~ Beta(alpha, beta)}. The Beta family is the conjugate
 * prior of the Bernoulli likelihood, so a sequence of binary observations
 * (an adjudicated agreement was, or was not, produced by a true match) updates
 * the belief in closed form by incrementing {@code alpha} on a confirmed match
 * and {@code beta} on a confirmed non-match.
 *
 * <p>The distribution is parameterised by two strictly positive shape
 * parameters. The following moments are used by the scoring engine:
 * <ul>
 *   <li>Posterior mean:
 *       {@code E[m] = alpha / (alpha + beta)}.</li>
 *   <li>Variance:
 *       {@code Var[m] = (alpha * beta) / ((alpha + beta)^2 * (alpha + beta + 1))}.</li>
 *   <li>Effective sample size (pseudo-count): {@code n = alpha + beta}. As
 *       {@code n} grows the variance shrinks as {@code O(1/n)}, so the belief
 *       concentrates on its mean.</li>
 * </ul>
 *
 * <p>The scoring engine consumes a conservative point estimate rather than the
 * mean. The lower confidence bound {@code E[m] - z * sd(m)} penalises reliance
 * on a signal type whose reliability is not yet well-established: when the
 * effective sample size is small the standard deviation is large and the bound
 * is pulled toward zero, which attenuates the evidential weight the type may
 * contribute. This mirrors the conservative-rating construction used in skill
 * inference, where a rating is reported as {@code mu - k*sigma} so that an
 * uncertain estimate cannot dominate a decision.
 *
 * <p>Instances are value types with structural equality and are safe for
 * concurrent read. Update methods return new instances and never mutate the
 * receiver.
 *
 * @param alpha positive prior/posterior count of match-consistent agreements
 * @param beta  positive prior/posterior count of non-match-consistent agreements
 */
public record BetaBelief(double alpha, double beta) {

    private static final double MIN_SHAPE = 1.0e-6;

    public BetaBelief {
        if (!(alpha > 0.0) || !Double.isFinite(alpha)) {
            throw new IllegalArgumentException("alpha must be finite and positive: " + alpha);
        }
        if (!(beta > 0.0) || !Double.isFinite(beta)) {
            throw new IllegalArgumentException("beta must be finite and positive: " + beta);
        }
    }

    /**
     * Constructs a belief from a prior mean and a pseudo-count (effective sample
     * size), the parameterisation used by the signal-type catalogue.
     *
     * <p>Given a mean {@code m in (0,1)} and a count {@code n > 0}, the shape
     * parameters are {@code alpha = m * n} and {@code beta = (1 - m) * n}, which
     * reproduces the requested mean exactly and encodes strength of prior belief
     * through {@code n}.
     *
     * @param mean  prior mean reliability, clamped to the open unit interval
     * @param count pseudo-count; the effective number of prior observations
     * @return the constructed belief
     */
    public static BetaBelief ofMean(final double mean, final double count) {
        final double m = clampUnit(mean);
        final double n = Math.max(count, 2.0 * MIN_SHAPE);
        return new BetaBelief(Math.max(m * n, MIN_SHAPE), Math.max((1.0 - m) * n, MIN_SHAPE));
    }

    /** Returns the posterior mean {@code alpha / (alpha + beta)}. */
    public double mean() {
        return alpha / (alpha + beta);
    }

    /** Returns the effective sample size {@code alpha + beta}. */
    public double count() {
        return alpha + beta;
    }

    /**
     * Returns the variance of the belief.
     *
     * @return {@code (alpha*beta) / ((alpha+beta)^2 * (alpha+beta+1))}
     */
    public double variance() {
        final double s = alpha + beta;
        return (alpha * beta) / (s * s * (s + 1.0));
    }

    /** Returns the standard deviation, the square root of {@link #variance()}. */
    public double stddev() {
        return Math.sqrt(variance());
    }

    /**
     * Returns the conservative lower confidence estimate of the reliability.
     *
     * <p>The bound is {@code clamp(mean - z * sd, 0, 1)}. A larger {@code z}
     * demands more evidence before a signal type is trusted; {@code z = 0}
     * recovers the plain posterior mean.
     *
     * @param z non-negative number of standard deviations of downward shift
     * @return the clamped lower confidence estimate in {@code [0, 1]}
     */
    public double lowerConfidence(final double z) {
        return clampUnit(mean() - z * stddev());
    }

    /**
     * Returns a belief reinforced by an adjudicated true match in which this
     * signal type agreed.
     *
     * @param weight positive observation weight (unit weight for a single event)
     * @return {@code Beta(alpha + weight, beta)}
     */
    public BetaBelief reinforce(final double weight) {
        return new BetaBelief(alpha + Math.max(weight, 0.0), beta);
    }

    /**
     * Returns a belief penalised by an adjudicated non-match in which this signal
     * type nonetheless agreed (a false-positive agreement).
     *
     * @param weight positive observation weight
     * @return {@code Beta(alpha, beta + weight)}
     */
    public BetaBelief penalize(final double weight) {
        return new BetaBelief(alpha, beta + Math.max(weight, 0.0));
    }

    private static double clampUnit(final double v) {
        if (v < MIN_SHAPE) {
            return MIN_SHAPE;
        }
        if (v > 1.0 - MIN_SHAPE) {
            return 1.0 - MIN_SHAPE;
        }
        return v;
    }
}