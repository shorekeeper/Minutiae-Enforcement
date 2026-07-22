package org.synergyst.minutiae.fingerprint;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Immutable configuration of the Bayesian evidence model.
 *
 * <p>The parameters govern the prior odds of evasion, the conservatism of the
 * reliability estimate, the per-signal evidential cap, and the posterior
 * probability at which a login is flagged.
 *
 * @param priorEvasionRate base rate {@code pi in (0,1)} of evasion among
 *                         connections, defining the prior log-odds
 *                         {@code log2(pi / (1 - pi))}; a small value renders the
 *                         model conservative, requiring substantial evidence to
 *                         overturn the presumption of a distinct entity
 * @param shrinkageZ       non-negative number of standard deviations by which
 *                         each reliability estimate is shifted downward before
 *                         use, attenuating the weight of poorly-established
 *                         signal types
 * @param weightCapBits    symmetric bound on the evidential weight, in bits,
 *                         that a single signal agreement may contribute,
 *                         bounding the influence of any one field
 * @param flagThreshold    posterior probability at or above which a candidate is
 *                         flagged as probable evasion
 */
public record EvidenceConfig(double priorEvasionRate,
                             double shrinkageZ,
                             double weightCapBits,
                             double flagThreshold) {

    public EvidenceConfig {
        if (!(priorEvasionRate > 0.0 && priorEvasionRate < 1.0)) {
            throw new IllegalArgumentException("priorEvasionRate must lie in (0,1): " + priorEvasionRate);
        }
        if (!(shrinkageZ >= 0.0) || !Double.isFinite(shrinkageZ)) {
            throw new IllegalArgumentException("shrinkageZ must be finite and non-negative: " + shrinkageZ);
        }
        if (!(weightCapBits > 0.0) || !Double.isFinite(weightCapBits)) {
            throw new IllegalArgumentException("weightCapBits must be finite and positive: " + weightCapBits);
        }
        if (!(flagThreshold > 0.0 && flagThreshold < 1.0)) {
            throw new IllegalArgumentException("flagThreshold must lie in (0,1): " + flagThreshold);
        }
    }

    /** The fail-safe default configuration. */
    public static EvidenceConfig defaults() {
        return new EvidenceConfig(1.0e-3, 0.8, 12.0, 0.90);
    }

    /**
     * Materialises configuration from a section, applying defaults for absent
     * values.
     *
     * @param section the {@code fingerprint.evidence} section, or null
     * @return an immutable configuration snapshot
     */
    public static EvidenceConfig from(final ConfigurationSection section) {
        if (section == null) {
            return defaults();
        }
        final EvidenceConfig d = defaults();
        return new EvidenceConfig(
                section.getDouble("prior-evasion-rate", d.priorEvasionRate()),
                section.getDouble("shrinkage-z", d.shrinkageZ()),
                section.getDouble("weight-cap-bits", d.weightCapBits()),
                section.getDouble("flag-threshold", d.flagThreshold()));
    }
}