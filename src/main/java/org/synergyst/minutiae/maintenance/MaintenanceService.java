package org.synergyst.minutiae.maintenance;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.synergyst.minutiae.behaviour.BehaviourEffects;
import org.synergyst.minutiae.behaviour.BehaviourManager;
import org.synergyst.minutiae.fingerprint.FingerprintEngine;
import org.synergyst.minutiae.fingerprint.SessionRegistry;
import org.synergyst.minutiae.lifecycle.LifecycleComponent;
import org.synergyst.minutiae.log.KernelLogger;

/**
 * Periodic background maintenance.
 *
 * <p>On each tick the service evicts stale fingerprint sessions past their
 * retention grace, sweeps fully-expired behavioural records to free memory, and
 * reconciles concealment for every online player so that a ghost or shadow whose
 * window has lapsed becomes visible again without requiring a rejoin. The
 * concealment reconciliation is skipped entirely when no behavioural state
 * exists, keeping the common idle case to a single map-emptiness check.
 *
 * <p>The service additionally amortises the rebuild of the fingerprint
 * value-frequency aggregate across sweeps. The aggregate underlies the
 * value-conditioned non-match likelihood of the evidence model and need not be
 * current to the instant; rebuilding it on a coarse multiple of the sweep period
 * bounds its staleness while keeping per-sweep cost negligible. The rebuild
 * itself is dispatched by the engine to the asynchronous scheduler and never
 * blocks the sweep.
 *
 * <p>The refresh cadence is expressed as a whole number of sweeps, computed once
 * at construction as the configured refresh period divided by the sweep period
 * and floored at one sweep. A running sweep counter triggers the refresh when it
 * reaches that multiple, after which it resets.
 *
 * <p>The task runs on the main thread because it consults the online-player set
 * and manipulates cross-player visibility. Its interval is coarse, so the
 * per-tick cost is amortised and negligible relative to the server tick budget.
 */
public final class MaintenanceService implements LifecycleComponent {

    private final KernelLogger log;
    private final JavaPlugin plugin;
    private final SessionRegistry sessions;
    private final BehaviourManager behaviour;
    private final BehaviourEffects effects;
    private final FingerprintEngine fingerprint;
    private final long intervalTicks;
    private final long sessionGraceMillis;
    private final long freqRefreshEverySweeps;

    private long sweepsSinceRefresh;
    private BukkitTask task;

    /**
     * Creates the maintenance service.
     *
     * @param log                 diagnostic logger
     * @param plugin              owning plugin, for scheduling and world access
     * @param sessions            fingerprint session registry to evict from
     * @param behaviour           behavioural state manager to sweep
     * @param effects             behavioural effects, for concealment reconcile
     * @param fingerprint         fingerprint engine, for frequency refresh
     * @param intervalSeconds     sweep period in seconds, floored at one second
     * @param sessionGraceSeconds session retention grace in seconds
     * @param freqRefreshSeconds  frequency-aggregate rebuild period in seconds,
     *                            floored at one second
     */
    public MaintenanceService(final KernelLogger log,
                              final JavaPlugin plugin,
                              final SessionRegistry sessions,
                              final BehaviourManager behaviour,
                              final BehaviourEffects effects,
                              final FingerprintEngine fingerprint,
                              final long intervalSeconds,
                              final long sessionGraceSeconds,
                              final long freqRefreshSeconds) {
        this.log = log;
        this.plugin = plugin;
        this.sessions = sessions;
        this.behaviour = behaviour;
        this.effects = effects;
        this.fingerprint = fingerprint;
        this.intervalTicks = Math.max(1L, intervalSeconds) * 20L;
        this.sessionGraceMillis = Math.max(0L, sessionGraceSeconds) * 1000L;
        // Convert the refresh period from seconds to sweeps: (period_ticks) /
        // (sweep_ticks), floored at one so a refresh occurs at least every sweep
        // when the configured period is shorter than the sweep period.
        final long refreshTicks = Math.max(1L, freqRefreshSeconds) * 20L;
        this.freqRefreshEverySweeps = Math.max(1L, refreshTicks / this.intervalTicks);
    }

    @Override
    public String tag() {
        return "maintenance";
    }

    @Override
    public void boot() {
        this.task = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::sweep, intervalTicks, intervalTicks);
        log.info("maintenance", "sweeper scheduled every %d tick(s); frequency refresh every %d sweep(s)",
                intervalTicks, freqRefreshEverySweeps);
    }

    @Override
    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
    }

    private void sweep() {
        final long now = System.currentTimeMillis();

        final int evicted = sessions.evictStale(now, sessionGraceMillis);
        final int removed = behaviour.sweepExpired(now);

        if (!behaviour.isEmpty()) {
            for (final Player player : plugin.getServer().getOnlinePlayers()) {
                effects.reconcileConcealment(player, now);
            }
        }

        // Amortise the frequency-aggregate rebuild across sweeps. The engine
        // dispatches the blocking refresh to the async scheduler, so this call
        // returns immediately and never stalls the tick.
        if (++sweepsSinceRefresh >= freqRefreshEverySweeps) {
            sweepsSinceRefresh = 0L;
            fingerprint.refreshFrequencies(now);
        }

        if (evicted > 0 || removed > 0) {
            log.trace("maintenance", "swept %d session(s), %d behaviour record(s)", evicted, removed);
        }
    }
}