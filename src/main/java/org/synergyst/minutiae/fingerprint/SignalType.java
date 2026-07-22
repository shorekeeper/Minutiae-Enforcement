package org.synergyst.minutiae.fingerprint;

/**
 * Enumeration of fingerprint signal types.
 *
 * <p>Each type carries a stable code, equal to its ordinal, persisted in the
 * {@code signals.type} column as a compact integer rather than a string. The
 * code is the authoritative on-disk identity; enum declaration order must never
 * be reordered once released, only appended to.
 *
 * <p>Each type additionally declares a reliability prior and a temporal decay
 * half-life used by the evidence model. The reliability prior is expressed as a
 * {@link BetaBelief} over {@code m_t = P(field agrees | same entity)}. The
 * legacy scalar weight is retained for signal capture and is not used by the
 * evidence model.
 *
 * <h2>Dependence families</h2>
 * <p>The evidence model combines agreements under a conditional-independence
 * assumption. That assumption is violated by construction for signals derived
 * from one underlying attribute: an agreement on the full address entails an
 * agreement on its subnet, its provider label, and its reverse-DNS pattern, so
 * summing their weights counts one observation several times. Each type
 * therefore declares a dependence family and a specificity rank within it.
 * Within one family, only the most specific agreeing signal contributes
 * evidential weight; agreements of coarser rank in the same family are
 * dominated and contribute nothing. A signal in the {@link Family#NONE} family
 * is independent of every other type.
 *
 * <p>Families and ranks: the {@link Family#ADDRESS} family orders
 * {@code IP_FULL} (0) over {@code IP_SUBNET} (1) over {@code IP_RDNS} (2) over
 * {@code IP_ASN} (3); the {@link Family#CLIENT} family orders
 * {@code CLIENT_BRAND} (0) over {@code CLIENT_PROTOCOL} (1), the protocol
 * version being largely determined by the brand.
 */
public enum SignalType {

    /** Full remote address of the connection. */
    IP_FULL("ip-full", 0.5d, 0.55d, 12.0d, 14L, Family.ADDRESS, 0),

    /** The /24 (IPv4) or /64 (IPv6) network prefix of the remote address. */
    IP_SUBNET("ip-subnet", 0.2d, 0.40d, 10.0d, 30L, Family.ADDRESS, 1),

    /** The client's reported locale. */
    LOCALE("locale", 0.1d, 0.30d, 8.0d, 365L, Family.NONE, 0),

    /** The client's reported brand string. */
    CLIENT_BRAND("client-brand", 0.15d, 0.35d, 8.0d, 90L, Family.CLIENT, 0),

    /** A digit-normalised pattern derived from the player name. */
    NAME_PATTERN("name-pattern", 0.1d, 0.45d, 8.0d, 0L, Family.NONE, 0),

    /** Autonomous-system or provider classification of the remote address. */
    IP_ASN("ip-asn", 0.12d, 0.30d, 8.0d, 120L, Family.ADDRESS, 3),

    /** Reverse-DNS host pattern of the remote address. */
    IP_RDNS("ip-rdns", 0.18d, 0.45d, 8.0d, 30L, Family.ADDRESS, 2),

    /** The client's declared protocol version. */
    CLIENT_PROTOCOL("client-protocol", 0.08d, 0.35d, 6.0d, 60L, Family.CLIENT, 1),

    /** The client's declared render (view) distance. */
    VIEW_DISTANCE("view-distance", 0.05d, 0.20d, 6.0d, 60L, Family.NONE, 0),

    /** The client's enabled skin-part bitmask. */
    SKIN_PARTS("skin-parts", 0.08d, 0.30d, 6.0d, 120L, Family.NONE, 0),

    /** The client's declared main hand. */
    MAIN_HAND("main-hand", 0.03d, 0.10d, 6.0d, 365L, Family.NONE, 0);

    /** Dependence family of a signal type. The set is closed. */
    public enum Family {

        /** Signals derived from the remote address. */
        ADDRESS,

        /** Signals derived from the client software identity. */
        CLIENT,

        /** Signals independent of every other type. */
        NONE
    }

    private static final long DAY_MS = 86_400_000L;

    private final String configKey;
    private final double defaultWeight;
    private final double priorMean;
    private final double priorCount;
    private final long halfLifeMillis;
    private final Family family;
    private final int familyRank;

    SignalType(final String configKey, final double defaultWeight,
               final double priorMean, final double priorCount, final long halfLifeDays,
               final Family family, final int familyRank) {
        this.configKey = configKey;
        this.defaultWeight = defaultWeight;
        this.priorMean = priorMean;
        this.priorCount = priorCount;
        this.halfLifeMillis = halfLifeDays * DAY_MS;
        this.family = family;
        this.familyRank = familyRank;
    }

    /** Returns the persisted integer code for this type. */
    public int code() {
        return ordinal();
    }

    /** Returns the configuration key used to override this type's parameters. */
    public String configKey() {
        return configKey;
    }

    /** Returns the capture-time scalar weight for this type. */
    public double defaultWeight() {
        return defaultWeight;
    }

    /** Returns this type's dependence family. */
    public Family family() {
        return family;
    }

    /**
     * Returns this type's specificity rank within its family; a lower rank is
     * more specific and dominates higher ranks of the same family.
     */
    public int familyRank() {
        return familyRank;
    }

    /**
     * Returns the prior belief over this type's match reliability.
     *
     * @return {@code Beta(priorMean * priorCount, (1 - priorMean) * priorCount)}
     */
    public BetaBelief reliabilityPrior() {
        return BetaBelief.ofMean(priorMean, priorCount);
    }

    /**
     * Returns the evidential half-life of this type in milliseconds.
     *
     * <p>A value of zero denotes evidence that does not decay. A positive value
     * {@code H} defines the exponential decay rate {@code lambda = ln 2 / H}, so
     * that an agreement of age {@code H} contributes half the evidential weight
     * of a fresh agreement.
     *
     * @return the half-life in milliseconds, or zero for non-decaying evidence
     */
    public long halfLifeMillis() {
        return halfLifeMillis;
    }

    /**
     * Resolves a type by its persisted code.
     *
     * @param code the integer code
     * @return the corresponding type
     * @throws IllegalArgumentException if the code is out of range
     */
    public static SignalType fromCode(final int code) {
        final SignalType[] values = values();
        if (code < 0 || code >= values.length) {
            throw new IllegalArgumentException("unknown signal type code " + code);
        }
        return values[code];
    }
}