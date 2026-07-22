package org.synergyst.minutiae;

import org.bukkit.plugin.java.JavaPlugin;
import org.synergyst.minutiae.boot.BootClock;
import org.synergyst.minutiae.boot.BootSequence;
import org.synergyst.minutiae.lifecycle.ServiceContainer;
import org.synergyst.minutiae.log.KernelLogger;

/**
 * Plugin entry point.
 *
 * <p>Owns the service container and the boot-relative clock. Enable delegates to
 * {@link BootSequence}; on any boot failure the container is unwound and the
 * plugin disables itself to avoid running in a partially-initialised state.
 * Disable performs deterministic reverse-order teardown of all managed
 * components.
 */
public final class MinutiaeEnforcement extends JavaPlugin {

    private BootClock clock;
    private KernelLogger kernelLog;
    private ServiceContainer services;

    @Override
    public void onLoad() {
        // Provisional logger for the load phase only. The boot clock is armed
        // at enable, where the measured boot sequence actually begins; arming
        // it here would fold world-generation latency into boot timings.
        this.kernelLog = new KernelLogger(getSLF4JLogger(), System.nanoTime(), true);
        kernelLog.info("core", "plugin loaded");
    }

    @Override
    public void onEnable() {
        final boolean verbose = getConfig().getBoolean("boot.verbose", true);
        // Arm the boot clock at the true start of the boot sequence so that all
        // stage timings are measured relative to enable, not load.
        this.clock = new BootClock();
        this.kernelLog = new KernelLogger(getSLF4JLogger(), clock.baselineNanos(), verbose);
        this.services = new ServiceContainer(kernelLog);

        try {
            new BootSequence(this, kernelLog, services).run();
        } catch (final Throwable t) {
            kernelLog.error("boot", t, "boot sequence aborted; disabling plugin");
            services.shutdownAll();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (services != null) {
            kernelLog.info("core", "beginning teardown");
            services.shutdownAll();
            kernelLog.info("core", "teardown complete");
        }
    }
}