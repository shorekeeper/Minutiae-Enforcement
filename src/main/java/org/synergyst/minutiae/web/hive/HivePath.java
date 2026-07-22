package org.synergyst.minutiae.web.hive;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An immutable, POSIX-like path addressing a Hive key.
 *
 * <p>A path is a sequence of non-empty segments. The root is the empty sequence,
 * rendered {@code "/"}. Parsing splits on {@code '/'} and discards empty
 * segments, so leading, trailing, and repeated separators are tolerated. Paths
 * are value types with structural equality.
 */
public final class HivePath {

    /** The root path. */
    public static final HivePath ROOT = new HivePath(new String[0]);

    private final String[] segments;

    private HivePath(final String[] segments) {
        this.segments = segments;
    }

    /**
     * Parses a path from its textual form.
     *
     * @param raw the textual path, already URL-decoded; null yields the root
     * @return the parsed path
     */
    public static HivePath parse(final String raw) {
        if (raw == null || raw.isEmpty() || raw.equals("/")) {
            return ROOT;
        }
        final List<String> out = new ArrayList<>();
        for (final String part : raw.split("/")) {
            if (!part.isEmpty()) {
                out.add(part);
            }
        }
        return new HivePath(out.toArray(new String[0]));
    }

    /** Returns the segment count; zero for the root. */
    public int depth() {
        return segments.length;
    }

    /**
     * Returns the segment at an index.
     *
     * @param i the zero-based index
     * @return the segment
     */
    public String seg(final int i) {
        return segments[i];
    }

    /** Returns the last segment, or the empty string for the root. */
    public String name() {
        return segments.length == 0 ? "" : segments[segments.length - 1];
    }

    /**
     * Returns a child path with an additional segment.
     *
     * @param segment the segment to append
     * @return the child path
     */
    public HivePath child(final String segment) {
        final String[] out = Arrays.copyOf(segments, segments.length + 1);
        out[segments.length] = segment;
        return new HivePath(out);
    }

    /** Returns the segment array as a copy. */
    public String[] segments() {
        return segments.clone();
    }

    @Override
    public String toString() {
        if (segments.length == 0) {
            return "/";
        }
        final StringBuilder sb = new StringBuilder();
        for (final String s : segments) {
            sb.append('/').append(s);
        }
        return sb.toString();
    }
}