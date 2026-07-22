package org.synergyst.minutiae.fingerprint;

/**
 * Classification of a remote address against a configured network catalogue.
 *
 * <p>{@code asn} is a coarse provider or autonomous-system label under which the
 * address falls; {@code datacenter} marks the address as belonging to a hosting,
 * proxy, or VPN network rather than a residential access network; {@code known}
 * reports whether the address matched any configured range at all.
 *
 * <p>The datacenter flag is not itself an identity signal. Its evidential effect
 * is mediated by the value-conditioned non-match likelihood of the scoring model:
 * datacenter and VPN networks are shared by many distinct accounts, so their
 * addresses and provider labels exhibit high collision frequency and are
 * therefore assigned low or negative evidential weight automatically.
 *
 * @param asn        provider or autonomous-system label, or null when unknown
 * @param datacenter whether the address belongs to a hosting or proxy network
 * @param known      whether the address matched any configured range
 */
public record NetworkClass(String asn, boolean datacenter, boolean known) {

    /** The classification of an address that matched no configured range. */
    public static final NetworkClass UNKNOWN = new NetworkClass(null, false, false);
}