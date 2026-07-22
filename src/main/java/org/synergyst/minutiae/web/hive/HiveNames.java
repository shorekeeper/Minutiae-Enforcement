package org.synergyst.minutiae.web.hive;

/**
 * Canonical hive names, prefixed {@code HKEY_} in the registry convention.
 *
 * <p>Each hive is a distinct concern rooted at the top of the namespace. The
 * cases hive holds the whole sanction tree, descending subject to measure to
 * enforcer to sanction, so that provenance is expressed by hierarchy rather than
 * by cross reference. The remaining hives collect appeals, amendments, and system
 * state. These strings are the first path segment under which each subtree is
 * mounted.
 */
public final class HiveNames {

    /** The sanction case tree, descending player, measure, enforcer, sanction. */
    public static final String CASES = "HKEY_CASES";

    /** Appeals, by appellant and in aggregate. */
    public static final String APPEALS = "HKEY_APPEALS";

    /** Amendments and their traceback histories. */
    public static final String AMENDS = "HKEY_AMENDS";

    /** System state: the automaton engine and the global audit trail. */
    public static final String SYSTEM = "HKEY_SYSTEM";

    /** The fingerprint forensic network: signals, clusters, and suspects. */
    public static final String FINGERPRINTS = "HKEY_FINGERPRINTS";

    private HiveNames() {
    }
}