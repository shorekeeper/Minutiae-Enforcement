package org.synergyst.minutiae.fingerprint;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
/**
 * Derives fingerprint signals from raw connection and session data.
 *
 * <p>The collector is stateless and side-effect free. It produces two shapes:
 * a {@link ProbeArrays} for the data available at login time, and a
 * {@link CaptureArrays} for the richer data accumulated over a session. Signals
 * whose source data is absent are omitted rather than represented by a
 * placeholder, so that a missing setting contributes nothing to scoring.
 *
 * <p>Two families of derivation are supplied. The address-and-name family
 * derives the full address, its network prefix, and a digit-normalised name
 * pattern, and is available before the client has fully connected. The extended
 * family adds a provider or autonomous-system label and a reverse-DNS host
 * pattern derived from the address through a {@link NetworkClassifier}, together
 * with the client locale, brand, protocol version, render distance, skin-part
 * bitmask, and main hand accumulated over the session.
 *
 * <p>Reverse-DNS resolution performs a blocking name lookup and is executed only
 * on the asynchronous pre-login thread and the asynchronous storage scheduler;
 * it is never invoked on the server main thread. When reverse DNS is disabled or
 * yields no PTR record, the corresponding signal is omitted.
 *
 * <p>Name-derived and host-derived patterns replace each decimal digit with a
 * hash. For a player name this clusters names differing only in a numeric
 * suffix, a common alt-account trait. For a reverse-DNS host this collapses the
 * address-encoded portion of a provider hostname, clustering hosts of the same
 * provider while discarding the address-specific component.
 */
public final class SignalCollector {

    private SignalCollector() {
    }

    // ----------------------------------------------------------------------
    // Legacy address-and-name family (retained for the linear scoring path)
    // ----------------------------------------------------------------------

    /**
     * Builds a login-time probe from the address and name available before the
     * player has fully connected.
     *
     * @param address remote address of the connection
     * @param name    connecting player name
     * @return the probe arrays
     */
    public static ProbeArrays probe(final InetAddress address, final String name) {
        final List<Integer> types = new ArrayList<>(3);
        final List<String> values = new ArrayList<>(3);
        add(types, values, SignalType.IP_FULL, fullIp(address));
        add(types, values, SignalType.IP_SUBNET, subnet(address));
        add(types, values, SignalType.NAME_PATTERN, namePattern(name));
        return new ProbeArrays(toIntArray(types), values.toArray(new String[0]));
    }

    /**
     * Builds a capture from an accumulated session, applying the supplied
     * weights, over the address-and-name family only.
     *
     * @param session the session to capture from
     * @param weights weights indexed by {@link SignalType#code()}
     * @return the capture arrays; may be empty when no source data is present
     */
    public static CaptureArrays capture(final Session session, final double[] weights) {
        return capture(session, weights, NetworkClassifier.empty(), false);
    }

    // ----------------------------------------------------------------------
    // Extended family
    // ----------------------------------------------------------------------

    /**
     * Builds a login-time probe from the address and name, extended with the
     * network classification and, optionally, the reverse-DNS host pattern.
     *
     * @param address     remote address of the connection
     * @param name        connecting player name
     * @param classifier  the network classifier
     * @param reverseDns  whether to perform reverse-DNS derivation
     * @return the probe arrays
     */
    public static ProbeArrays probe(final InetAddress address, final String name,
                                    final NetworkClassifier classifier, final boolean reverseDns) {
        final List<Integer> types = new ArrayList<>(5);
        final List<String> values = new ArrayList<>(5);

        add(types, values, SignalType.IP_FULL, fullIp(address));
        add(types, values, SignalType.IP_SUBNET, subnet(address));
        add(types, values, SignalType.NAME_PATTERN, namePattern(name));

        final NetworkClass nc = classifier.classify(address);
        if (nc.known() && nc.asn() != null) {
            add(types, values, SignalType.IP_ASN, nc.asn());
        }
        if (reverseDns) {
            add(types, values, SignalType.IP_RDNS, rdnsPattern(address));
        }
        return new ProbeArrays(toIntArray(types), values.toArray(new String[0]));
    }

    /**
     * Builds a capture from an accumulated session over the full signal family,
     * applying the supplied weights.
     *
     * <p>Every available signal is captured; a signal whose source value is
     * absent, blank, or at its integer sentinel is skipped. The weight for each
     * captured signal is taken from the weights array by the signal type's code.
     *
     * @param session     the session to capture from
     * @param weights     weights indexed by {@link SignalType#code()}
     * @param classifier  the network classifier
     * @param reverseDns  whether to perform reverse-DNS derivation
     * @return the capture arrays; may be empty when no source data is present
     */
    public static CaptureArrays capture(final Session session, final double[] weights,
                                        final NetworkClassifier classifier, final boolean reverseDns) {
        final List<Integer> types = new ArrayList<>(11);
        final List<String> values = new ArrayList<>(11);

        final InetAddress address = session.address();
        if (address != null) {
            add(types, values, SignalType.IP_FULL, fullIp(address));
            add(types, values, SignalType.IP_SUBNET, subnet(address));
            final NetworkClass nc = classifier.classify(address);
            if (nc.known() && nc.asn() != null) {
                add(types, values, SignalType.IP_ASN, nc.asn());
            }
            if (reverseDns) {
                add(types, values, SignalType.IP_RDNS, rdnsPattern(address));
            }
        }

        add(types, values, SignalType.LOCALE, blankToNull(session.locale()));
        add(types, values, SignalType.CLIENT_BRAND, blankToNull(session.brand()));
        add(types, values, SignalType.NAME_PATTERN, namePattern(session.name()));

        if (session.protocolVersion() != Session.UNKNOWN_INT) {
            add(types, values, SignalType.CLIENT_PROTOCOL, Integer.toString(session.protocolVersion()));
        }
        if (session.viewDistance() != Session.UNKNOWN_INT) {
            add(types, values, SignalType.VIEW_DISTANCE, Integer.toString(session.viewDistance()));
        }
        if (session.skinParts() != Session.UNKNOWN_INT) {
            add(types, values, SignalType.SKIN_PARTS, Integer.toString(session.skinParts()));
        }
        add(types, values, SignalType.MAIN_HAND, blankToNull(session.mainHand()));

        final int n = types.size();
        final int[] typeArr = new int[n];
        final double[] weightArr = new double[n];
        for (int i = 0; i < n; i++) {
            final int code = types.get(i);
            typeArr[i] = code;
            weightArr[i] = weights[code];
        }
        return new CaptureArrays(typeArr, values.toArray(new String[0]), weightArr);
    }

    // ----------------------------------------------------------------------
    // Value derivations
    // ----------------------------------------------------------------------

    /**
     * Renders the full textual form of a remote address.
     *
     * @param address the address
     * @return the host-address string, or null when the address is null
     */
    public static String fullIp(final InetAddress address) {
        return address == null ? null : address.getHostAddress();
    }

    /**
     * Derives the network prefix of a remote address: the /24 prefix for IPv4
     * addresses and the /64 prefix for IPv6 addresses.
     *
     * @param address the address
     * @return the prefix string, or null when the address is null
     */
    public static String subnet(final InetAddress address) {
        if (address == null) {
            return null;
        }
        final byte[] b = address.getAddress();
        if (b.length == 4) {
            return (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + ".0/24";
        }
        final StringBuilder sb = new StringBuilder(24);
        for (int group = 0; group < 4; group++) {
            if (group > 0) {
                sb.append(':');
            }
            final int hi = b[group * 2] & 0xFF;
            final int lo = b[group * 2 + 1] & 0xFF;
            sb.append(Integer.toHexString((hi << 8) | lo));
        }
        sb.append("::/64");
        return sb.toString();
    }

    /**
     * Derives a digit-normalised pattern from a player name by replacing each
     * decimal digit with a hash.
     *
     * @param name the player name
     * @return the pattern, or null when the name is null or empty
     */
    public static String namePattern(final String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        final char[] chars = name.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            final char c = chars[i];
            if (c >= '0' && c <= '9') {
                chars[i] = '#';
            }
        }
        return new String(chars);
    }

    /**
     * Derives a lowercase, digit-normalised pattern from the reverse-DNS host of
     * an address, bounding the underlying lookup by a deadline and by pool
     * capacity. Callers must invoke this only off the server main thread.
     *
     * @param address the address
     * @return the host pattern, or null when the address is null, no PTR record
     *         resolves, the lookup exceeds its deadline, or the resolver pool is
     *         saturated
     */
    public static String rdnsPattern(final InetAddress address) {
        if (address == null) {
            return null;
        }
        final String canonical = resolveCanonicalBounded(address);
        if (canonical == null || canonical.isEmpty()) {
            return null;
        }
        // A resolver that finds no PTR record returns the textual address; that
        // carries no host structure and is treated as absent.
        if (canonical.equalsIgnoreCase(address.getHostAddress())) {
            return null;
        }
        final char[] chars = canonical.toLowerCase(Locale.ROOT).toCharArray();
        for (int i = 0; i < chars.length; i++) {
            final char c = chars[i];
            if (c >= '0' && c <= '9') {
                chars[i] = '#';
            }
        }
        return new String(chars);
    }

    private static String resolveCanonicalBounded(final InetAddress address) {
        final Future<String> future;
        try {
            future = RDNS_POOL.submit(address::getCanonicalHostName);
        } catch (final RejectedExecutionException saturated) {
            return null;
        }
        try {
            return future.get(RDNS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (final TimeoutException | ExecutionException e) {
            future.cancel(true);
            return null;
        } catch (final InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    /**
     * Reverse-DNS resolution runs a blocking, non-interruptible native lookup.
     * It is confined to a capacity-bounded pool that rejects work once saturated,
     * so a flood of addresses lacking a PTR record cannot exhaust threads, and a
     * synchronous handoff queue ensures rejection rather than unbounded queueing.
     */
    private static final ThreadPoolExecutor RDNS_POOL = new ThreadPoolExecutor(
            0, 16, 30L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            r -> {
                final Thread t = new Thread(r, "minutiae-rdns");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    private static final long RDNS_TIMEOUT_MS = 1_500L;

    private static void add(final List<Integer> types, final List<String> values,
                            final SignalType type, final String value) {
        if (value != null) {
            types.add(type.code());
            values.add(value);
        }
    }

    private static String blankToNull(final String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static int[] toIntArray(final List<Integer> list) {
        final int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }
}