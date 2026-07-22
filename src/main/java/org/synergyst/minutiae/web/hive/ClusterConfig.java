package org.synergyst.minutiae.web.hive;

import org.bukkit.configuration.ConfigurationSection;

import org.synergyst.minutiae.fingerprint.SignalType;

/**
 * Immutable configuration of the two-phase account-cluster computation.
 *
 * <p>The cluster graph distinguishes two edge classes. A hard link is a shared
 * signal value rare enough that its coincidental co-occurrence across distinct
 * people is negligible; hard links are unioned unconditionally, consolidating the
 * accounts they connect into a single super-node. A soft link is a shared value
 * common enough to arise by chance; soft links do not union outright but
 * accumulate weighted evidence between super-nodes, and two super-nodes are
 * merged only when their aggregate soft evidence crosses a threshold.
 *
 * <p>The classification of a shared value is governed by the number of distinct
 * accounts bearing it, denoted its bearer count {@code b}:
 * <ul>
 *   <li>{@code b <= hardMaxBearers} and the value's signal type is hard-eligible:
 *       the value is a hard link.</li>
 *   <li>{@code hardMaxBearers < b <= softMaxBearers}, or the type is not
 *       hard-eligible with {@code b <= softMaxBearers}: the value is a soft
 *       link.</li>
 *   <li>{@code b > softMaxBearers}: the value is discarded, contributing no
 *       edge. A value borne by a large fraction of the corpus (a public proxy, a
 *       ubiquitous locale) carries no identifying information and would otherwise
 *       fuse unrelated accounts into a spurious mega-cluster.</li>
 * </ul>
 *
 * <p>The soft-link weight of a shared value is its inverse-document-frequency
 * evidence in bits, {@code log2(N / b)}, where {@code N} is the corpus size. A
 * value common within the retained soft band still contributes little, and a
 * value near the hard boundary contributes much, so soft merges require either
 * one strong soft link or several weak ones.
 *
 * @param hardMaxBearers  inclusive upper bound on the bearer count of a hard link
 * @param softMaxBearers  inclusive upper bound on the bearer count of any
 *                        retained edge; values above are discarded
 * @param softMergeBits   accumulated soft-edge weight, in bits, at or above which
 *                        two super-nodes are merged
 * @param hardEligible    per-signal-type hard-link eligibility, indexed by
 *                        {@link SignalType#code()}
 */
public record ClusterConfig(int hardMaxBearers,
                            int softMaxBearers,
                            double softMergeBits,
                            boolean[] hardEligible) {

    /**
     * Returns the fail-safe default configuration.
     *
     * <p>Only address-precise and host-precise signal types are hard-eligible by
     * default, since these are the values whose rare sharing most strongly
     * implies a single operator. Coarse or low-entropy types (subnet, locale,
     * view distance, main hand) never form hard links regardless of rarity.
     *
     * @return the default configuration
     */
    public static ClusterConfig defaults() {
        final boolean[] eligible = new boolean[SignalType.values().length];
        eligible[SignalType.IP_FULL.code()] = true;
        eligible[SignalType.NAME_PATTERN.code()] = true;
        return new ClusterConfig(3, 12, 8.0d, eligible);
    }

    /**
     * Materialises configuration from a section, applying defaults for absent
     * values. Hard-eligibility overrides are read from the {@code hard-eligible}
     * string list, whose entries are signal type configuration keys.
     *
     * @param section the {@code fingerprint.clustering} section, or null
     * @return an immutable configuration snapshot
     */
    public static ClusterConfig from(final ConfigurationSection section) {
        final ClusterConfig d = defaults();
        if (section == null) {
            return d;
        }
        final int hardMax = section.getInt("hard-max-bearers", d.hardMaxBearers());
        final int softMax = section.getInt("soft-max-bearers", d.softMaxBearers());
        final double mergeBits = section.getDouble("soft-merge-bits", d.softMergeBits());

        // An explicit list, when present, fully replaces the default eligibility
        // set; otherwise the defaults are retained.
        boolean[] eligible = d.hardEligible();
        if (section.isList("hard-eligible")) {
            eligible = new boolean[SignalType.values().length];
            for (final String key : section.getStringList("hard-eligible")) {
                for (final SignalType t : SignalType.values()) {
                    if (t.configKey().equalsIgnoreCase(key)) {
                        eligible[t.code()] = true;
                    }
                }
            }
        }
        return new ClusterConfig(hardMax, softMax, mergeBits, eligible);
    }
}