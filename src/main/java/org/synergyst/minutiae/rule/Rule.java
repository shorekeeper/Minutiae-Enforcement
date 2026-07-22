package org.synergyst.minutiae.rule;

/**
 * An authoritative rule entry.
 *
 * <p>The {@code contentHash} is a stable digest over the identifier and
 * description, used for cheap equality and staleness detection against the
 * persisted rule cache. Two {@code Rule} values with identical identifier and
 * description always yield the same hash; the digest is deterministic across
 * runs and platforms and is not derived from {@link String#hashCode()}.
 *
 * @param id          canonical rule identifier (e.g. {@code §3.2})
 * @param description authoritative human-readable description
 * @param contentHash FNV-1a 32-bit digest of {@code id} and {@code description}
 */
public record Rule(String id, String description, int contentHash) {

    private static final int FNV_OFFSET_BASIS = 0x811C9DC5;
    private static final int FNV_PRIME = 0x01000193;

    /**
     * Constructs a rule, computing its content hash from the supplied fields.
     *
     * @param id          canonical rule identifier
     * @param description authoritative description
     * @return a fully populated rule
     */
    public static Rule of(final String id, final String description) {
        return new Rule(id, description, digest(id, description));
    }

    private static int digest(final String id, final String description) {
        int h = FNV_OFFSET_BASIS;
        for (int i = 0, n = id.length(); i < n; i++) {
            h = (h ^ id.charAt(i)) * FNV_PRIME;
        }
        // NUL separator prevents ("ab","c") and ("a","bc") from colliding.
        h = (h ^ 0x00) * FNV_PRIME;
        for (int i = 0, n = description.length(); i < n; i++) {
            h = (h ^ description.charAt(i)) * FNV_PRIME;
        }
        return h;
    }
}