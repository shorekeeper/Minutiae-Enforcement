package org.synergyst.minutiae.net;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.synergyst.minutiae.log.KernelLogger;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Detector of a missing proxy ip-forwarding configuration.
 *
 * <p>Behind a proxy without address forwarding every connection presents the
 * proxy's address. The consequence is silent and severe: fingerprint address
 * signals collapse onto one value, corrupting evasion scoring and account
 * clustering, while nothing visibly fails. The sentinel watches the first
 * connections of a session and raises one prominent warning when a
 * threshold of distinct accounts has connected while exactly one distinct
 * source address has been observed - the symptomatic pattern of unforwarded
 * proxy traffic. The heuristic cannot fire on a healthy standalone server,
 * where distinct players present distinct addresses under any realistic
 * audience beyond a single household.
 *
 * <p>The sentinel observes at monitor priority, never mutates the event, and
 * disarms itself permanently after either verdict.
 */
public final class ForwardingSentinel implements Listener {

    private static final int SAMPLE_ACCOUNTS = 8;

    private final KernelLogger log;
    private final Set<UUID> accounts = ConcurrentHashMap.newKeySet();
    private final Set<String> addresses = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean armed = new AtomicBoolean(true);

    public ForwardingSentinel(final KernelLogger log) {
        this.log = log;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        if (!armed.get() || event.getAddress() == null) {
            return;
        }
        accounts.add(event.getUniqueId());
        addresses.add(event.getAddress().getHostAddress());

        if (accounts.size() < SAMPLE_ACCOUNTS) {
            return;
        }
        if (!armed.compareAndSet(true, false)) {
            return;
        }
        if (addresses.size() == 1) {
            log.error("network",
                    "%d distinct accounts connected from a single address (%s):"
                            + " this is the signature of a proxy WITHOUT ip forwarding."
                            + " Fingerprint address signals are corrupted in this state."
                            + " Enable Velocity modern forwarding or BungeeCord ip_forward"
                            + " with the matching Paper configuration.",
                    accounts.size(), addresses.iterator().next());
        } else {
            log.trace("network", "forwarding sentinel disarmed: %d address(es) across"
                    + " %d account(s)", addresses.size(), accounts.size());
        }
        accounts.clear();
        addresses.clear();
    }
}