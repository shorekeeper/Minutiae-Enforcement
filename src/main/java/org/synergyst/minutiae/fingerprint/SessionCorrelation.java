package org.synergyst.minutiae.fingerprint;

/**
 * Pure computation of the behavioural log-odds adjustment between two accounts
 * from their session intervals and hour-of-day activity histograms.
 *
 * <p>The adjustment captures three temporal observations that field agreement
 * alone cannot express, each motivated by the operational distinction between an
 * alternate account (one operator, several identities) and co-located distinct
 * people (several operators sharing an access network).
 *
 * <h2>Simultaneous presence (negative evidence)</h2>
 *
 * <p>Two identities operated by one person are rarely online at the same instant;
 * two distinct people sharing a household are frequently online together. Let a
 * session interval be {@code [login, end]}, where {@code end} is the recorded
 * logout or, for an open interval, the current time. Two intervals overlap when
 * {@code A.login < B.end} and {@code B.login < A.end}; the overlap duration is
 * {@code max(0, min(A.end, B.end) - max(A.login, B.login))}. The count of
 * overlapping pairs, capped at {@code overlapCap}, contributes negative evidence
 * at {@code overlapBits} bits per pair.
 *
 * <h2>Handoff transitions (positive evidence)</h2>
 *
 * <p>A single operator alternating accounts produces a characteristic handoff:
 * one account disconnects and the other connects, from the same address, within
 * a short gap. For an ordered pair of intervals {@code (X, Y)} on the same
 * address, a handoff is counted when {@code Y.login - X.end} lies in
 * {@code [0, handoffGapMillis]}. Both directions are considered. The handoff
 * count, capped at {@code handoffCap}, contributes positive evidence at
 * {@code handoffBits} bits per handoff. The shared-address constraint is enforced
 * by comparing 64-bit FNV-1a hashes of the address strings; a zero hash denotes
 * an unknown address and never matches.
 *
 * <h2>Active-hour similarity (weak positive evidence)</h2>
 *
 * <p>Accounts operated by one person tend to be active in the same hours of the
 * day. Let {@code a} and {@code b} be the two 24-bin hour-of-day histograms. Their
 * cosine similarity {@code c = (a . b) / (||a|| ||b||)} lies in {@code [0, 1]} for
 * non-negative vectors, with the convention {@code c = 0} when either vector is
 * zero. Because a shared activity window is common across unrelated players, the
 * term contributes only above a threshold: it adds
 * {@code hoursBits * (c - hoursCenter) / (1 - hoursCenter)} bits when
 * {@code c > hoursCenter}, and nothing otherwise, so its maximum is
 * {@code hoursBits} at {@code c = 1}.
 *
 * <h2>Aggregation and bounding</h2>
 *
 * <p>The three terms sum to a raw adjustment which is clamped to the symmetric
 * interval {@code [-totalCapBits, totalCapBits]}. This adjustment is a bounded
 * additive prior shift on the log-odds scale, not a likelihood ratio derived
 * from a calibrated generative model; its role is to bias the field-agreement
 * posterior toward or away from a match by an amount that cannot, by
 * construction, override the field evidence.
 *
 * <h2>Complexity</h2>
 *
 * <p>Overlap and handoff detection are quadratic in the per-account interval
 * count, which is bounded by the configured interval limit, and operate on
 * primitive parallel arrays with no allocation on the numeric path. Cosine
 * similarity is linear in the fixed 24 bins. The computation performs no I/O and
 * holds no state; it is safe for concurrent use.
 */
public final class SessionCorrelation {

    private static final double LN2 = 0.6931471805599453d;

    private final SessionCorrelationConfig config;

    public SessionCorrelation(final SessionCorrelationConfig config) {
        this.config = config;
    }

    /**
     * Correlates two accounts from their session intervals and activity
     * histograms, supplied in structure-of-arrays form.
     *
     * <p>The three per-account interval arrays are parallel: for interval
     * {@code i}, {@code logins[i]} is the login time, {@code logouts[i]} the
     * logout time (zero for an open interval), and {@code ipHash[i]} the FNV-1a
     * hash of the login address (zero when unknown). The histograms are length-24
     * hit-count arrays indexed by hour of day.
     *
     * @param loginsA  account A login times
     * @param logoutsA account A logout times, zero when open
     * @param ipHashA  account A per-interval address hashes
     * @param histA    account A hour-of-day histogram, length 24
     * @param loginsB  account B login times
     * @param logoutsB account B logout times, zero when open
     * @param ipHashB  account B per-interval address hashes
     * @param histB    account B hour-of-day histogram, length 24
     * @param now      current time in epoch milliseconds, used as the end of open
     *                 intervals
     * @return the correlation score
     */
    public CorrelationScore correlate(final long[] loginsA, final long[] logoutsA, final long[] ipHashA,
                                      final long[] histA,
                                      final long[] loginsB, final long[] logoutsB, final long[] ipHashB,
                                      final long[] histB, final long now) {
        final int na = loginsA.length;
        final int nb = loginsB.length;

        int overlapPairs = 0;
        long overlapMillis = 0L;
        int handoffs = 0;
        final long gap = config.handoffGapMillis();

        for (int i = 0; i < na; i++) {
            final long aStart = loginsA[i];
            final long aEnd = logoutsA[i] == 0L ? now : logoutsA[i];
            for (int j = 0; j < nb; j++) {
                final long bStart = loginsB[j];
                final long bEnd = logoutsB[j] == 0L ? now : logoutsB[j];

                if (aStart < bEnd && bStart < aEnd) {
                    overlapPairs++;
                    final long lo = Math.max(aStart, bStart);
                    final long hi = Math.min(aEnd, bEnd);
                    if (hi > lo) {
                        overlapMillis += (hi - lo);
                    }
                }

                if (ipHashA[i] != 0L && ipHashA[i] == ipHashB[j]) {
                    final long fwd = bStart - aEnd;
                    if (fwd >= 0L && fwd <= gap) {
                        handoffs++;
                    }
                    final long bwd = aStart - bEnd;
                    if (bwd >= 0L && bwd <= gap) {
                        handoffs++;
                    }
                }
            }
        }

        final double similarity = cosine(histA, histB);
        final double bits = adjustment(overlapPairs, handoffs, similarity);
        return new CorrelationScore(bits, overlapPairs, handoffs, overlapMillis, similarity);
    }

    private double adjustment(final int overlapPairs, final int handoffs, final double similarity) {
        double b = 0.0d;
        b += config.handoffBits() * Math.min(handoffs, config.handoffCap());
        b -= config.overlapBits() * Math.min(overlapPairs, config.overlapCap());
        if (similarity > config.hoursCenter()) {
            final double denom = 1.0d - config.hoursCenter();
            final double scaled = denom <= 0.0d ? 1.0d : (similarity - config.hoursCenter()) / denom;
            b += config.hoursBits() * scaled;
        }
        final double cap = config.totalCapBits();
        return b > cap ? cap : (b < -cap ? -cap : b);
    }

    /**
     * Returns the cosine similarity of two non-negative histograms.
     *
     * @param a first histogram
     * @param b second histogram
     * @return the similarity in {@code [0, 1]}, or zero when either is a zero
     *         vector or lengths differ
     */
    public static double cosine(final long[] a, final long[] b) {
        if (a.length != b.length) {
            return 0.0d;
        }
        double dot = 0.0d;
        double na = 0.0d;
        double nb = 0.0d;
        for (int i = 0; i < a.length; i++) {
            final double x = a[i];
            final double y = b[i];
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        if (na <= 0.0d || nb <= 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /**
     * Computes the 64-bit FNV-1a hash of an address string, the shared-address
     * key used by handoff detection.
     *
     * @param ip the address string, or null
     * @return the hash, or zero when the input is null or empty
     */
    public static long ipHash(final String ip) {
        if (ip == null || ip.isEmpty()) {
            return 0L;
        }
        long h = 0xCBF29CE484222325L;
        for (int i = 0, n = ip.length(); i < n; i++) {
            h ^= ip.charAt(i);
            h *= 0x100000001B3L;
        }
        // Fold zero to a non-zero sentinel so a legitimate hash never collides
        // with the "unknown address" marker.
        return h == 0L ? 1L : h;
    }

    /** Returns the natural-log-of-two constant used by callers converting bits. */
    public static double ln2() {
        return LN2;
    }
}