package org.synergyst.minutiae.execute;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.synergyst.minutiae.lifecycle.LifecycleComponent;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.message.Arg;
import org.synergyst.minutiae.message.MessageKey;
import org.synergyst.minutiae.notify.NotificationService;
import org.synergyst.minutiae.storage.SanctionView;
import org.synergyst.minutiae.storage.Storage;

import java.util.List;

/**
 * Poller of scheduled sanction reviews.
 *
 * <p>A sanction issued with the {@code review} annotation carries a review
 * timestamp; once the timestamp passes, the sanction is due for staff
 * attention. The service polls the shared backend on a fixed asynchronous
 * schedule, claims due sanctions transactionally (the claim clears the review
 * mark, so a sanction is reviewed exactly once even across several instances
 * sharing one backend), and dispatches one notification per claimed sanction
 * through the default channels.
 *
 * <p>The poll runs off the main thread; notification delivery is marshalled
 * by the notification service according to each channel's threading rules. A
 * poll failure is logged and retried on the next cycle; claimed rows whose
 * notification fails are not re-claimed, an accepted trade-off that avoids
 * duplicate review traffic.
 */
public final class ReviewService implements LifecycleComponent {

    private static final long POLL_TICKS = 20L * 60L;
    private static final int CLAIM_LIMIT = 16;

    private final KernelLogger log;
    private final JavaPlugin plugin;
    private final Storage storage;
    private final NotificationService notifications;

    private BukkitTask task;

    public ReviewService(final KernelLogger log,
                         final JavaPlugin plugin,
                         final Storage storage,
                         final NotificationService notifications) {
        this.log = log;
        this.plugin = plugin;
        this.storage = storage;
        this.notifications = notifications;
    }

    @Override
    public String tag() {
        return "review";
    }

    @Override
    public void boot() {
        this.task = plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, this::poll, POLL_TICKS, POLL_TICKS);
        log.info("review", "poller scheduled every %d tick(s)", POLL_TICKS);
    }

    @Override
    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
    }

    private void poll() {
        final List<SanctionView> due;
        try {
            due = storage.claimReviewDue(System.currentTimeMillis(), CLAIM_LIMIT).join();
        } catch (final RuntimeException e) {
            log.warn("review", "review poll failed: %s", e.getMessage());
            return;
        }
        for (final SanctionView v : due) {
            notifications.dispatch(List.of(), MessageKey.NOTIFY_REVIEW,
                    Arg.n("id", v.id()),
                    Arg.measure(v.measure()),
                    Arg.s("staff", v.staff()));
            log.info("review", "sanction #%d flagged for review", v.id());
        }
    }
}