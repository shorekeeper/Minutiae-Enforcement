package org.synergyst.minutiae.fingerprint;

import org.bukkit.configuration.ConfigurationSection;
import org.synergyst.minutiae.log.KernelLogger;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Immutable longest-prefix classifier of remote addresses against a configured
 * catalogue of CIDR ranges.
 *
 * <p>Each catalogue entry binds a CIDR range to a provider or autonomous-system
 * label and a datacenter flag. Classification of an address returns the label
 * and flag of the most specific range containing it, that is, the matching range
 * of greatest prefix length. When no range contains the address the classifier
 * returns {@link NetworkClass#UNKNOWN}.
 *
 * <h2>Representation</h2>
 *
 * <p>Ranges are held in a structure-of-arrays layout partitioned by address
 * family, so that classification touches only primitive arrays and performs no
 * allocation and no boxing. IPv4 ranges are stored as a network integer, a mask
 * integer, and a prefix length; IPv6 ranges as a network and mask pair of longs
 * spanning the 128-bit address, plus a prefix length. Within each family, ranges
 * are sorted by prefix length in descending order, so that the first range whose
 * masked network equals the masked query is the longest-prefix match. The scan
 * is therefore linear in the number of ranges of the query's family with an
 * early return on first match, and requires no auxiliary trie.
 *
 * <h2>Address decoding</h2>
 *
 * <p>An {@link InetAddress} is decoded to four bytes (IPv4) or sixteen bytes
 * (IPv6). A four-byte address is packed big-endian into one integer; a
 * sixteen-byte address is packed big-endian into a high long (bytes 0..7) and a
 * low long (bytes 8..15). Mask application uses arithmetic on these packed forms.
 * An IPv4-mapped IPv6 address, if presented as sixteen bytes, is matched against
 * the IPv6 catalogue as written; operators wishing to match such addresses
 * should catalogue them in their presented family.
 *
 * <h2>Mask construction</h2>
 *
 * <p>For an IPv4 prefix {@code p in [0,32]}, the mask is {@code 0} when
 * {@code p = 0} and {@code -1 << (32 - p)} otherwise. For an IPv6 prefix
 * {@code p in [0,128]}, the high mask covers bits {@code 0..63} and the low mask
 * bits {@code 64..127}: the high mask is {@code -1} when {@code p >= 64},
 * {@code 0} when {@code p = 0}, and {@code -1L << (64 - p)} otherwise; the low
 * mask is {@code 0} when {@code p <= 64} and {@code -1L << (128 - p)} otherwise.
 * These identities are exact under two's-complement shift semantics for the
 * stated ranges.
 *
 * <p>The classifier is built once and never mutated; all published state is
 * final, so instances are safe for concurrent classification.
 */
public final class NetworkClassifier {

    private final int[] v4network;
    private final int[] v4mask;
    private final String[] v4asn;
    private final boolean[] v4datacenter;

    private final long[] v6hi;
    private final long[] v6lo;
    private final long[] v6maskHi;
    private final long[] v6maskLo;
    private final String[] v6asn;
    private final boolean[] v6datacenter;

    private NetworkClassifier(final int[] v4network, final int[] v4mask, final String[] v4asn,
                              final boolean[] v4datacenter, final long[] v6hi, final long[] v6lo,
                              final long[] v6maskHi, final long[] v6maskLo, final String[] v6asn,
                              final boolean[] v6datacenter) {
        this.v4network = v4network;
        this.v4mask = v4mask;
        this.v4asn = v4asn;
        this.v4datacenter = v4datacenter;
        this.v6hi = v6hi;
        this.v6lo = v6lo;
        this.v6maskHi = v6maskHi;
        this.v6maskLo = v6maskLo;
        this.v6asn = v6asn;
        this.v6datacenter = v6datacenter;
    }

    /** Returns a classifier with no catalogued ranges. */
    public static NetworkClassifier empty() {
        return new NetworkClassifier(new int[0], new int[0], new String[0], new boolean[0],
                new long[0], new long[0], new long[0], new long[0], new String[0], new boolean[0]);
    }

    private record Entry(int prefix, int v4net, int v4mask,
                         long v6hi, long v6lo, long v6maskHi, long v6maskLo,
                         boolean ipv6, String asn, boolean datacenter) {
    }

    /**
     * Builds a classifier from the {@code entries} list of a configuration
     * section. Each entry is a mapping with a {@code cidr} string, an optional
     * {@code asn} label, and an optional boolean {@code datacenter} flag. A
     * malformed entry is reported and skipped; a defect in one entry does not
     * abort the load.
     *
     * @param section the {@code fingerprint.network} section, or null
     * @param log     diagnostic logger
     * @return the constructed classifier
     */
    public static NetworkClassifier build(final ConfigurationSection section, final KernelLogger log) {
        if (section == null) {
            return empty();
        }
        final List<Entry> entries = new ArrayList<>();
        for (final Object raw : section.getList("entries", List.of())) {
            if (!(raw instanceof java.util.Map<?, ?> map)) {
                log.warn("fingerprint", "network entry is not a mapping; skipped");
                continue;
            }
            final Object cidrObj = map.get("cidr");
            if (cidrObj == null) {
                log.warn("fingerprint", "network entry has no 'cidr'; skipped");
                continue;
            }
            final String cidr = String.valueOf(cidrObj).trim();
            final String asn = map.get("asn") == null ? null : String.valueOf(map.get("asn"));
            final boolean datacenter = Boolean.parseBoolean(String.valueOf(map.get("datacenter")));
            final Entry parsed = parse(cidr, asn, datacenter, log);
            if (parsed != null) {
                entries.add(parsed);
            }
        }
        // Sort by prefix length descending so the first match is the longest.
        entries.sort((a, b) -> Integer.compare(b.prefix(), a.prefix()));

        final List<Entry> v4 = entries.stream().filter(e -> !e.ipv6()).toList();
        final List<Entry> v6 = entries.stream().filter(Entry::ipv6).toList();

        final int[] v4net = new int[v4.size()];
        final int[] v4msk = new int[v4.size()];
        final String[] v4asn = new String[v4.size()];
        final boolean[] v4dc = new boolean[v4.size()];
        for (int i = 0; i < v4.size(); i++) {
            final Entry e = v4.get(i);
            v4net[i] = e.v4net();
            v4msk[i] = e.v4mask();
            v4asn[i] = e.asn();
            v4dc[i] = e.datacenter();
        }

        final long[] v6hi = new long[v6.size()];
        final long[] v6lo = new long[v6.size()];
        final long[] v6mhi = new long[v6.size()];
        final long[] v6mlo = new long[v6.size()];
        final String[] v6asn = new String[v6.size()];
        final boolean[] v6dc = new boolean[v6.size()];
        for (int i = 0; i < v6.size(); i++) {
            final Entry e = v6.get(i);
            v6hi[i] = e.v6hi();
            v6lo[i] = e.v6lo();
            v6mhi[i] = e.v6maskHi();
            v6mlo[i] = e.v6maskLo();
            v6asn[i] = e.asn();
            v6dc[i] = e.datacenter();
        }

        log.info("fingerprint", "network classifier: %d IPv4 range(s), %d IPv6 range(s)",
                v4.size(), v6.size());
        return new NetworkClassifier(v4net, v4msk, v4asn, v4dc,
                v6hi, v6lo, v6mhi, v6mlo, v6asn, v6dc);
    }

    /**
     * Classifies an address against the catalogue.
     *
     * @param address the address, possibly null
     * @return the classification, or {@link NetworkClass#UNKNOWN} when the
     *         address is null or matches no range
     */
    public NetworkClass classify(final InetAddress address) {
        if (address == null) {
            return NetworkClass.UNKNOWN;
        }
        final byte[] b = address.getAddress();
        if (b.length == 4) {
            final int q = pack32(b);
            for (int i = 0; i < v4network.length; i++) {
                if ((q & v4mask[i]) == v4network[i]) {
                    return new NetworkClass(v4asn[i], v4datacenter[i], true);
                }
            }
        } else if (b.length == 16) {
            final long qhi = pack64(b, 0);
            final long qlo = pack64(b, 8);
            for (int i = 0; i < v6hi.length; i++) {
                if ((qhi & v6maskHi[i]) == v6hi[i] && (qlo & v6maskLo[i]) == v6lo[i]) {
                    return new NetworkClass(v6asn[i], v6datacenter[i], true);
                }
            }
        }
        return NetworkClass.UNKNOWN;
    }

    private static Entry parse(final String cidr, final String asn, final boolean datacenter,
                               final KernelLogger log) {
        final int slash = cidr.indexOf('/');
        if (slash <= 0) {
            log.warn("fingerprint", "malformed CIDR '%s'; skipped", cidr);
            return null;
        }
        final String addrPart = cidr.substring(0, slash);
        final int prefix;
        try {
            prefix = Integer.parseInt(cidr.substring(slash + 1).trim());
        } catch (final NumberFormatException e) {
            log.warn("fingerprint", "malformed CIDR prefix in '%s'; skipped", cidr);
            return null;
        }
        final InetAddress net;
        try {
            net = InetAddress.getByName(addrPart);
        } catch (final Exception e) {
            log.warn("fingerprint", "malformed CIDR address in '%s'; skipped", cidr);
            return null;
        }
        final byte[] b = net.getAddress();
        if (b.length == 4) {
            if (prefix < 0 || prefix > 32) {
                log.warn("fingerprint", "IPv4 prefix out of range in '%s'; skipped", cidr);
                return null;
            }
            final int mask = prefix == 0 ? 0 : (-1 << (32 - prefix));
            final int network = pack32(b) & mask;
            return new Entry(prefix, network, mask, 0L, 0L, 0L, 0L, false, asn, datacenter);
        }
        if (b.length == 16) {
            if (prefix < 0 || prefix > 128) {
                log.warn("fingerprint", "IPv6 prefix out of range in '%s'; skipped", cidr);
                return null;
            }
            final long maskHi = prefix >= 64 ? -1L : (prefix == 0 ? 0L : (-1L << (64 - prefix)));
            final long maskLo = prefix <= 64 ? 0L : (-1L << (128 - prefix));
            final long hi = pack64(b, 0) & maskHi;
            final long lo = pack64(b, 8) & maskLo;
            return new Entry(prefix, 0, 0, hi, lo, maskHi, maskLo, true, asn, datacenter);
        }
        log.warn("fingerprint", "unrecognised address family in '%s'; skipped", cidr);
        return null;
    }

    private static int pack32(final byte[] b) {
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    private static long pack64(final byte[] b, final int off) {
        long v = 0L;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xFFL);
        }
        return v;
    }

    /** Returns a lowercase, digit-normalised label unused here; reserved for callers. */
    static String normalise(final String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}