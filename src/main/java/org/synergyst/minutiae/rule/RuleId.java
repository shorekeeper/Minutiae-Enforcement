package org.synergyst.minutiae.rule;

/**
 * Validation for canonical rule identifiers.
 *
 * <p>A valid identifier consists of the literal prefix {@code "P."} followed by
 * one or more numeric segments separated by single dots (e.g. {@code "P.3.2"}).
 * Each segment must contain at least one decimal digit. No leading, trailing, or
 * consecutive dots are permitted beyond the prefix, and no characters other than
 * digits and dots may follow the prefix.
 *
 * <p>The identifier is intentionally restricted to ASCII. It avoids the section
 * sign (U+00A7), which doubles as the client-side formatting-code introducer and
 * is unsafe to carry through chat rendering, logging, and text serialisation.
 *
 * <p>Validation is a single linear scan without regular expressions,
 * allocations, or backtracking.
 */
public final class RuleId {

    /** Mandatory identifier prefix. */
    public static final String PREFIX = "P.";

    private RuleId() {
    }

    /**
     * Validates an identifier against the canonical grammar.
     *
     * @param id the identifier to validate
     * @return {@code null} if the identifier is well-formed, otherwise a short
     *         diagnostic describing the first violation encountered
     */
    public static String validate(final String id) {
        if (id == null || id.isEmpty()) {
            return "empty identifier";
        }
        if (!id.startsWith(PREFIX)) {
            return "missing '" + PREFIX + "' prefix";
        }
        if (id.length() == PREFIX.length()) {
            return "no segment after '" + PREFIX + "'";
        }

        int digitsInSegment = 0;
        for (int i = PREFIX.length(), n = id.length(); i < n; i++) {
            final char c = id.charAt(i);
            if (c >= '0' && c <= '9') {
                digitsInSegment++;
            } else if (c == '.') {
                if (digitsInSegment == 0) {
                    return "empty segment before '.'";
                }
                digitsInSegment = 0;
            } else {
                return "illegal character '" + c + "' at position " + i;
            }
        }
        if (digitsInSegment == 0) {
            return "trailing '.'";
        }
        return null;
    }
}