package org.synergyst.minutiae.fingerprint;

import java.net.InetAddress;

/**
 * Mutable, incrementally-populated record of a player's current connection.
 *
 * <p>A session is created at pre-login with the remote address and name, then
 * enriched over the connection lifecycle with data that becomes available only
 * after the client has negotiated and reported its settings: the client locale,
 * brand, protocol version, render distance, enabled skin parts, and main hand.
 *
 * <p>Every field is written from one of three threads and read from a fourth:
 * the asynchronous pre-login thread (address, name), the main-thread join and
 * client-options handlers (locale, brand, protocol, render distance, skin parts,
 * main hand), and the asynchronous storage scheduler during signal capture.
 * Fields are declared {@code volatile} so that each reference or value is
 * published atomically; no compound invariant spans two fields, so a per-field
 * happens-before edge is sufficient and no coarser synchronisation is required.
 *
 * <p>Integer-valued client settings use a sentinel of {@code -1} to denote
 * "not yet known"; object-valued settings use {@code null}. A capturing consumer
 * omits any signal whose source value is at its sentinel, so a setting the client
 * never reported simply contributes no evidence.
 */
public final class Session {

    /** Sentinel for an integer client setting that has not yet been reported. */
    public static final int UNKNOWN_INT = -1;

    private final String name;
    private volatile InetAddress address;
    private volatile String locale;
    private volatile String brand;
    private volatile int protocolVersion = UNKNOWN_INT;
    private volatile int viewDistance = UNKNOWN_INT;
    private volatile int skinParts = UNKNOWN_INT;
    private volatile String mainHand;
    private volatile long lastActivity;

    /**
     * Creates a session with the data available at pre-login.
     *
     * @param name    connecting player name
     * @param address remote address
     */
    public Session(final String name, final InetAddress address) {
        this.name = name;
        this.address = address;
    }

    /** Returns the player name. */
    public String name() {
        return name;
    }

    /** Returns the remote address, or null when unknown. */
    public InetAddress address() {
        return address;
    }

    /** Updates the remote address. */
    public void address(final InetAddress value) {
        this.address = value;
    }

    /** Returns the client locale, or null when not yet known. */
    public String locale() {
        return locale;
    }

    /** Updates the client locale. */
    public void locale(final String value) {
        this.locale = value;
    }

    /** Returns the client brand, or null when not yet known. */
    public String brand() {
        return brand;
    }

    /** Updates the client brand. */
    public void brand(final String value) {
        this.brand = value;
    }

    /** Returns the declared protocol version, or {@link #UNKNOWN_INT}. */
    public int protocolVersion() {
        return protocolVersion;
    }

    /** Updates the declared protocol version. */
    public void protocolVersion(final int value) {
        this.protocolVersion = value;
    }

    /** Returns the declared render (view) distance, or {@link #UNKNOWN_INT}. */
    public int viewDistance() {
        return viewDistance;
    }

    /** Updates the declared render (view) distance. */
    public void viewDistance(final int value) {
        this.viewDistance = value;
    }

    /** Returns the enabled skin-part bitmask, or {@link #UNKNOWN_INT}. */
    public int skinParts() {
        return skinParts;
    }

    /** Updates the enabled skin-part bitmask. */
    public void skinParts(final int value) {
        this.skinParts = value;
    }

    /** Returns the declared main hand, or null when not yet known. */
    public String mainHand() {
        return mainHand;
    }

    /** Updates the declared main hand. */
    public void mainHand(final String value) {
        this.mainHand = value;
    }

    /** Returns the last-activity timestamp in epoch milliseconds. */
    public long lastActivity() {
        return lastActivity;
    }

    /** Stamps the last-activity timestamp to the given time. */
    public void touch(final long now) {
        this.lastActivity = now;
    }
}