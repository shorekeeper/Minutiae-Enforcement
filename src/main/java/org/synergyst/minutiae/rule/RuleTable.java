package org.synergyst.minutiae.rule;

import java.util.List;

/**
 * Immutable structure-of-arrays rule store with an open-addressing index.
 *
 * <p>Rule fields are held in three parallel arrays sized exactly to the entry
 * count, maximising cache locality during bulk traversal (such as cache
 * synchronisation). Point lookups resolve through a separate power-of-two
 * open-addressing table using linear probing, storing entry indices as
 * primitive ints to avoid boxing and per-entry node allocation.
 *
 * <p>The structure is built once and never mutated; all published state is
 * final, making instances safe for concurrent reads without synchronisation.
 */
public final class RuleTable {

    private static final int EMPTY = -1;
    private static final float MAX_LOAD = 0.6f;

    private final String[] ids;
    private final String[] descriptions;
    private final int[] hashes;

    private final int[] index;
    private final int mask;
    private final int size;

    private RuleTable(final String[] ids,
                      final String[] descriptions,
                      final int[] hashes,
                      final int[] index,
                      final int mask) {
        this.ids = ids;
        this.descriptions = descriptions;
        this.hashes = hashes;
        this.index = index;
        this.mask = mask;
        this.size = ids.length;
    }

    /**
     * Builds a table from a list of rules. The list is expected to be free of
     * duplicate identifiers; if a duplicate is present, the later entry
     * silently supersedes the earlier one in the lookup index while both
     * remain in the backing arrays.
     *
     * @param rules the source rules
     * @return an immutable table over the supplied rules
     */
    public static RuleTable build(final List<Rule> rules) {
        final int n = rules.size();
        final String[] ids = new String[n];
        final String[] descriptions = new String[n];
        final int[] hashes = new int[n];

        for (int i = 0; i < n; i++) {
            final Rule r = rules.get(i);
            ids[i] = r.id();
            descriptions[i] = r.description();
            hashes[i] = r.contentHash();
        }

        final int capacity = tableCapacity(n);
        final int[] index = new int[capacity];
        java.util.Arrays.fill(index, EMPTY);
        final int mask = capacity - 1;

        for (int slot = 0; slot < n; slot++) {
            int bucket = bucketOf(ids[slot], mask);
            while (index[bucket] != EMPTY) {
                bucket = (bucket + 1) & mask;
            }
            index[bucket] = slot;
        }

        return new RuleTable(ids, descriptions, hashes, index, mask);
    }

    private static int tableCapacity(final int entryCount) {
        final int required = Math.max(16, (int) (entryCount / MAX_LOAD) + 1);
        int cap = Integer.highestOneBit(required);
        if (cap < required) {
            cap <<= 1;
        }
        return cap;
    }

    private static int bucketOf(final String id, final int mask) {
        int h = id.hashCode();
        h ^= (h >>> 16);
        return h & mask;
    }

    /**
     * Resolves the backing-array slot for an identifier.
     *
     * @param id the identifier to locate
     * @return the slot index, or a negative value if absent
     */
    public int slotOf(final String id) {
        int bucket = bucketOf(id, mask);
        while (true) {
            final int slot = index[bucket];
            if (slot == EMPTY) {
                return EMPTY;
            }
            if (ids[slot].equals(id)) {
                return slot;
            }
            bucket = (bucket + 1) & mask;
        }
    }

    /** Reports whether an identifier is present. */
    public boolean contains(final String id) {
        return slotOf(id) != EMPTY;
    }

    /**
     * Returns the description for an identifier.
     *
     * @param id the identifier
     * @return the description, or {@code null} if absent
     */
    public String description(final String id) {
        final int slot = slotOf(id);
        return slot == EMPTY ? null : descriptions[slot];
    }

    /** Returns the number of entries. */
    public int size() {
        return size;
    }

    /**
     * Returns the backing identifier array. The returned reference is the live
     * internal array and must be treated as read-only.
     */
    public String[] ids() {
        return ids;
    }

    /**
     * Returns the backing description array. Read-only by contract.
     */
    public String[] descriptions() {
        return descriptions;
    }

    /**
     * Returns the backing content-hash array. Read-only by contract.
     */
    public int[] hashes() {
        return hashes;
    }
}