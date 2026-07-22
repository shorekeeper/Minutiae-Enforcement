package org.synergyst.minutiae.web.hive;

import org.synergyst.minutiae.storage.SignalLinkRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Precomputed connected-component index over the account-signal graph, resolved
 * by a two-phase hard-link then soft-link procedure.
 *
 * <p>Two accounts are related when they share at least one captured signal value.
 * Not all shared values carry equal weight: a value borne by few accounts is
 * strong evidence of a single operator, while a value borne by many arises by
 * chance. Resolving components by any-shared-value union conflates the two and
 * fuses unrelated accounts through a common proxy. This index separates the two
 * regimes.
 *
 * <h2>Phase one: hard-link consolidation</h2>
 *
 * <p>For each shared value whose bearer count is at or below
 * {@link ClusterConfig#hardMaxBearers()} and whose signal type is hard-eligible,
 * all accounts bearing the value are unioned by weighted union-find with path
 * compression. The result is a partition of the accounts into super-nodes, each
 * a set of accounts joined by at least one hard link and therefore almost
 * certainly a single entity.
 *
 * <h2>Phase two: soft-link accumulation and merge</h2>
 *
 * <p>For each shared value classified as a soft link, its inverse-document-
 * frequency weight {@code w = log2(N / b)} in bits is accumulated onto the edge
 * between every pair of distinct super-nodes represented among its bearers, where
 * {@code N} is the corpus size and {@code b} the bearer count. Soft-link values
 * are bounded in bearer count by {@link ClusterConfig#softMaxBearers()}, so the
 * pairwise accumulation per value is bounded by that constant squared; values
 * above the bound are discarded before this phase. After accumulation, every edge
 * whose total weight reaches {@link ClusterConfig#softMergeBits()} triggers a
 * union of its two super-nodes on the same disjoint-set structure. Accumulation
 * completes before any soft merge is applied, so the super-node identities used
 * as edge endpoints are stable throughout accumulation.
 *
 * <h2>Retention and enumeration</h2>
 *
 * <p>Only final components of two or more accounts are retained; a singleton
 * shares no informative value and forms no cluster of interest. Each retained
 * component is keyed by its representative, defined as the lexicographically
 * smallest member UUID, so that the key is stable across rebuilds and independent
 * of input order. Components are enumerated by descending member count with a
 * lexicographic tie-break on the representative.
 *
 * <h2>Representation and complexity</h2>
 *
 * <p>Account UUIDs are relabelled to dense integers on first sight. Disjoint-set
 * state is held in primitive parent and rank arrays. Per-run member deduplication
 * uses a generation-stamped integer array, giving amortised constant-time
 * membership without a hash set. Soft edges are accumulated in a map keyed by a
 * packed 64-bit pair of super-node roots. The whole construction is linear in the
 * number of input edges plus, per soft value, quadratic in the bounded soft
 * bearer count; it performs no allocation on the union or find paths.
 *
 * <p>The index is immutable after construction and safe for concurrent read.
 * Construction requires the input edges to be ordered by {@code (type, value)} so
 * that the bearers of one value form a contiguous run.
 */
public final class ClusterIndex {

    private static final double INV_LN2 = 1.0d / 0.6931471805599453d;

    private final Map<String, List<String>> clusters;
    private final Map<String, String> memberToRep;
    private final int memberCount;
    private final long builtAt;

    private ClusterIndex(final Map<String, List<String>> clusters,
                         final Map<String, String> memberToRep,
                         final int memberCount,
                         final long builtAt) {
        this.clusters = clusters;
        this.memberToRep = memberToRep;
        this.memberCount = memberCount;
        this.builtAt = builtAt;
    }

    /**
     * Builds the index from ordered signal-to-account edges.
     *
     * @param links         edges ordered by {@code (type, value)}
     * @param config        the cluster classification and merge configuration
     * @param totalAccounts corpus size {@code N} used in the soft-link weight
     * @param now           the build timestamp in epoch milliseconds
     * @return the constructed index
     */
    public static ClusterIndex build(final List<SignalLinkRow> links, final ClusterConfig config,
                                     final long totalAccounts, final long now) {
        // Pass one: assign each distinct UUID a dense integer index. The dense
        // labelling lets the disjoint-set structure use primitive arrays.
        final HashMap<String, Integer> index = new HashMap<>(Math.max(16, links.size()));
        final ArrayList<String> uuids = new ArrayList<>();
        for (final SignalLinkRow r : links) {
            if (index.putIfAbsent(r.uuid(), uuids.size()) == null) {
                uuids.add(r.uuid());
            }
        }
        final int n = uuids.size();

        // Disjoint-set state. parent[i] is the parent of node i; a root points to
        // itself. rank[i] bounds the tree height for union-by-rank.
        final int[] parent = new int[n];
        final byte[] rank = new byte[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }

        // Deduplication scratch: stamp[i] holds the generation in which node i was
        // last added to the current run, avoiding a per-run hash set.
        final int[] stamp = new int[n];
        java.util.Arrays.fill(stamp, -1);
        int generation = 0;

        final double nAccounts = Math.max((double) totalAccounts, 2.0d);
        final int m = links.size();

        // Reusable run buffer, grown once to the soft bound; a run exceeding the
        // soft bound is discarded, so the buffer never needs to exceed it plus a
        // small slack for detection.
        int[] runMembers = new int[Math.max(16, config.softMaxBearers() + 4)];

        // ---- Phase one: hard-link union ----------------------------------
        int i = 0;
        while (i < m) {
            final SignalLinkRow head = links.get(i);
            final int type = head.type();
            final String value = head.value();

            generation++;
            int count = 0;
            int j = i;
            // Collect the distinct account indices of this contiguous value run.
            while (j < m) {
                final SignalLinkRow r = links.get(j);
                if (r.type() != type || !r.value().equals(value)) {
                    break;
                }
                final int idx = index.get(r.uuid());
                if (stamp[idx] != generation) {
                    stamp[idx] = generation;
                    if (count == runMembers.length) {
                        runMembers = grow(runMembers);
                    }
                    runMembers[count++] = idx;
                }
                j++;
            }

            final boolean hard = config.hardEligible()[type] && count <= config.hardMaxBearers();
            if (hard) {
                // Union every member of a hard run into one super-node.
                for (int k = 1; k < count; k++) {
                    union(parent, rank, runMembers[0], runMembers[k]);
                }
            }
            i = j;
        }

        // ---- Phase two: soft-link accumulation ---------------------------
        // Soft edges are keyed by a packed pair of (post-hard) super-node roots.
        final HashMap<Long, Double> softEdges = new HashMap<>();
        int[] rootScratch = new int[Math.max(16, config.softMaxBearers() + 4)];

        i = 0;
        while (i < m) {
            final SignalLinkRow head = links.get(i);
            final int type = head.type();
            final String value = head.value();

            generation++;
            int count = 0;
            int j = i;
            boolean overflow = false;
            while (j < m) {
                final SignalLinkRow r = links.get(j);
                if (r.type() != type || !r.value().equals(value)) {
                    break;
                }
                final int idx = index.get(r.uuid());
                if (stamp[idx] != generation) {
                    stamp[idx] = generation;
                    // A run exceeding the soft bound is discarded; stop growing.
                    if (count > config.softMaxBearers()) {
                        overflow = true;
                    } else {
                        if (count == runMembers.length) {
                            runMembers = grow(runMembers);
                        }
                        runMembers[count] = idx;
                    }
                    count++;
                }
                j++;
            }
            i = j;

            // A value is a soft link only when it is not a hard link and its
            // bearer count lies within the retained soft band.
            final boolean hard = config.hardEligible()[type] && count <= config.hardMaxBearers();
            if (hard || overflow || count > config.softMaxBearers() || count < 2) {
                continue;
            }

            // Reduce the run's members to their distinct super-node roots.
            if (count > rootScratch.length) {
                rootScratch = new int[count];
            }
            int rootCount = 0;
            for (int k = 0; k < count; k++) {
                final int root = find(parent, runMembers[k]);
                boolean seen = false;
                for (int q = 0; q < rootCount; q++) {
                    if (rootScratch[q] == root) {
                        seen = true;
                        break;
                    }
                }
                if (!seen) {
                    rootScratch[rootCount++] = root;
                }
            }
            if (rootCount < 2) {
                continue;
            }

            // Inverse-document-frequency weight of this shared value, in bits.
            final double bits = Math.max(0.0d, log2(nAccounts / (double) count));

            // Accumulate the weight onto every distinct super-node pair.
            for (int a = 0; a < rootCount; a++) {
                for (int b = a + 1; b < rootCount; b++) {
                    final long key = pairKey(rootScratch[a], rootScratch[b]);
                    softEdges.merge(key, bits, Double::sum);
                }
            }
        }

        // Apply soft merges for edges whose accumulated weight reaches threshold.
        for (final Map.Entry<Long, Double> e : softEdges.entrySet()) {
            if (e.getValue() >= config.softMergeBits()) {
                final long key = e.getKey();
                union(parent, rank, (int) (key >>> 32), (int) (key & 0xFFFFFFFFL));
            }
        }

        // ---- Collect and order the final components ----------------------
        final HashMap<Integer, ArrayList<String>> byRoot = new HashMap<>();
        for (int k = 0; k < n; k++) {
            byRoot.computeIfAbsent(find(parent, k), x -> new ArrayList<>()).add(uuids.get(k));
        }

        final ArrayList<ArrayList<String>> retained = new ArrayList<>();
        int members = 0;
        for (final ArrayList<String> group : byRoot.values()) {
            if (group.size() < 2) {
                continue;
            }
            group.sort(Comparator.naturalOrder());
            retained.add(group);
            members += group.size();
        }
        retained.sort((a, b) -> {
            final int c = Integer.compare(b.size(), a.size());
            return c != 0 ? c : a.get(0).compareTo(b.get(0));
        });

        final LinkedHashMap<String, List<String>> clusters = new LinkedHashMap<>();
        final HashMap<String, String> memberToRep = new HashMap<>();
        for (final ArrayList<String> group : retained) {
            final String rep = group.get(0);
            clusters.put(rep, List.copyOf(group));
            for (final String member : group) {
                memberToRep.put(member, rep);
            }
        }
        return new ClusterIndex(clusters, memberToRep, members, now);
    }

    // ---- Disjoint-set primitives -----------------------------------------

    private static int find(final int[] parent, final int x) {
        int root = x;
        while (parent[root] != root) {
            root = parent[root];
        }
        // Path compression: point every node on the path directly at the root.
        int cur = x;
        while (parent[cur] != root) {
            final int next = parent[cur];
            parent[cur] = root;
            cur = next;
        }
        return root;
    }

    private static void union(final int[] parent, final byte[] rank, final int a, final int b) {
        final int ra = find(parent, a);
        final int rb = find(parent, b);
        if (ra == rb) {
            return;
        }
        // Union by rank: attach the shallower tree beneath the deeper one.
        if (rank[ra] < rank[rb]) {
            parent[ra] = rb;
        } else if (rank[ra] > rank[rb]) {
            parent[rb] = ra;
        } else {
            parent[rb] = ra;
            rank[ra]++;
        }
    }

    private static long pairKey(final int a, final int b) {
        // Order the endpoints so an edge has one canonical key regardless of the
        // order in which its endpoints are presented.
        final int lo = Math.min(a, b);
        final int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    private static int[] grow(final int[] a) {
        final int[] out = new int[a.length << 1];
        System.arraycopy(a, 0, out, 0, a.length);
        return out;
    }

    private static double log2(final double x) {
        return Math.log(x) * INV_LN2;
    }

    // ---- Read surface -----------------------------------------------------

    /** Returns the build timestamp in epoch milliseconds. */
    public long builtAt() {
        return builtAt;
    }

    /** Returns the number of retained clusters. */
    public int clusterCount() {
        return clusters.size();
    }

    /** Returns the total number of accounts across all retained clusters. */
    public int memberCount() {
        return memberCount;
    }

    /** Returns the cluster representatives in enumeration order. */
    public List<String> reps() {
        return new ArrayList<>(clusters.keySet());
    }

    /**
     * Reports whether a representative identifies a retained cluster.
     *
     * @param rep the representative UUID
     * @return {@code true} when a cluster is keyed by the representative
     */
    public boolean hasCluster(final String rep) {
        return clusters.containsKey(rep);
    }

    /**
     * Returns the members of a cluster.
     *
     * @param rep the representative UUID
     * @return the member UUIDs, ascending, or an empty list when absent
     */
    public List<String> members(final String rep) {
        return clusters.getOrDefault(rep, List.of());
    }

    /**
     * Returns the representative of the cluster containing a member.
     *
     * @param member the member UUID
     * @return the representative UUID, or null when the member is in no cluster
     */
    public String repOf(final String member) {
        return memberToRep.get(member);
    }
}