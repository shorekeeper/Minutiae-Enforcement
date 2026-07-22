package org.synergyst.minutiae.async;

import org.synergyst.minutiae.lifecycle.LifecycleComponent;
import org.synergyst.minutiae.log.KernelLogger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Off-thread execution facility for I/O-bound work.
 *
 * <p>All storage access is dispatched through this scheduler; the server main
 * thread never performs blocking I/O. Tasks execute on a virtual-thread-per-task
 * executor. Virtual threads are appropriate here because storage tasks are
 * dominated by blocking waits on JDBC calls rather than CPU work, and the
 * physical concurrency of those calls is separately bounded by the connection
 * pool. Task submission is therefore effectively unbounded and non-blocking on
 * the caller.
 *
 * <p>Completion stages returned by this scheduler complete on a scheduler
 * thread. Callers that must observe results on the main thread are responsible
 * for re-scheduling via the Bukkit scheduler.
 */
public final class AsyncScheduler implements LifecycleComponent {

    private final KernelLogger log;
    private final ExecutorService executor;

    public AsyncScheduler(final KernelLogger log) {
        this.log = log;
        final AtomicLong seq = new AtomicLong();
        final ThreadFactory factory = Thread.ofVirtual()
                .name("minutiae-io-", 0)
                .factory();
        this.executor = Executors.newThreadPerTaskExecutor(r -> {
            final Thread t = factory.newThread(r);
            // Sequence suffix aids correlation in thread dumps.
            t.setName("minutiae-io-" + seq.getAndIncrement());
            return t;
        });
    }

    /**
     * Submits a value-producing task for asynchronous execution.
     *
     * @param supplier the task; exceptions it throws complete the returned
     *                 stage exceptionally
     * @param <T>      result type
     * @return a stage completed on a scheduler thread
     */
    public <T> CompletableFuture<T> supply(final Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor);
    }

    /**
     * Submits a side-effecting task for asynchronous execution.
     *
     * @param task the task; exceptions it throws complete the returned stage
     *             exceptionally
     * @return a stage completed on a scheduler thread
     */
    public CompletableFuture<Void> run(final Runnable task) {
        return CompletableFuture.runAsync(task, executor);
    }

    @Override
    public String tag() {
        return "async";
    }

    @Override
    public void boot() {
        log.trace("async", "virtual-thread executor initialised");
    }

    @Override
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                final int dropped = executor.shutdownNow().size();
                log.warn("async", "forced termination; %d task(s) discarded", dropped);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}