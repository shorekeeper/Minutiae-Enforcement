package org.synergyst.minutiae.command;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.synergyst.minutiae.command.dsl.Args;
import org.synergyst.minutiae.command.dsl.Ctx;
import org.synergyst.minutiae.command.dsl.Node;
import org.synergyst.minutiae.message.Arg;
import org.synergyst.minutiae.message.MessageKey;
import org.synergyst.minutiae.message.MessageService;
import org.synergyst.minutiae.notify.NotificationService;
import org.synergyst.minutiae.storage.Storage;

import java.util.List;

/**
 * The {@code /appeal} command, by which a sanctioned player contests a sanction.
 *
 * <p>Submission is gated on three conditions verified against the target
 * sanction: the sanction must be marked appealable, must belong to the invoking
 * player, and must not already carry a pending appeal. A successful submission
 * persists the appeal and notifies reviewing staff through the notification
 * service. Storage callbacks deliver their user-facing replies directly, in the
 * same manner as the diagnostic and enforcement command trees.
 *
 * <p>The identifier argument completes from the recent-identifier ring,
 * narrowed to sanctions whose subject is the invoking player, so a player sees
 * only identifiers that could plausibly be theirs. A successful submission
 * records the appeal identifier into the ring for staff-side review
 * completion.
 */
public final class AppealCommand {

    private static final String PERMISSION = "minutiae.appeal.submit";

    private final Storage storage;
    private final MessageService messages;
    private final NotificationService notifications;
    private final RecentIds recents;
    private final JavaPlugin plugin;

    public AppealCommand(final Storage storage,
                         final MessageService messages,
                         final NotificationService notifications,
                         final RecentIds recents,
                         final JavaPlugin plugin) {
        this.storage = storage;
        this.messages = messages;
        this.notifications = notifications;
        this.recents = recents;
        this.plugin = plugin;
    }

    /**
     * Builds the command tree.
     *
     * @return the root node
     */
    public Node tree() {
        return Node.literal("appeal")
                .permission(PERMISSION)
                .then(Node.argument("id", Args.integer(1, Integer.MAX_VALUE))
                        .suggests(ctx -> ctx.sender() instanceof Player player
                                ? recents.sanctionIdsOf(player.getUniqueId())
                                : List.of())
                        .then(Node.argument("text", Args.greedy())
                                .executes(this::submit)));
    }

    private void submit(final Ctx ctx) {
        if (!(ctx.sender() instanceof Player player)) {
            messages.send(ctx.sender(), MessageKey.APPEAL_PLAYER_ONLY);
            return;
        }
        final long id = ctx.integer("id");
        final String text = ctx.str("text");
        final String uuid = player.getUniqueId().toString();

        storage.findSanction(id).thenAccept(view -> {
            if (view == null) {
                messages.send(player, MessageKey.LIFT_NOT_FOUND, Arg.n("id", id));
                return;
            }
            if (!view.uuid().equals(uuid)) {
                messages.send(player, MessageKey.APPEAL_NOT_YOURS);
                return;
            }
            if (view.liftedAt() != 0L) {
                messages.send(player, MessageKey.APPEAL_ALREADY_LIFTED);
                return;
            }
            if (view.appealable() != 1) {
                messages.send(player, MessageKey.APPEAL_NOT_APPEALABLE);
                return;
            }
            storage.submitAppeal(id, player.getName(), text, System.currentTimeMillis())
                    .thenAccept(appealId -> {
                        if (appealId <= 0L) {
                            messages.send(player, MessageKey.APPEAL_DUPLICATE, Arg.n("id", id));
                            return;
                        }
                        recents.recordAppeal(appealId, player.getUniqueId());
                        messages.send(player, MessageKey.APPEAL_SUBMITTED,
                                Arg.n("id", id), Arg.n("appeal", appealId));
                        notifications.dispatch(List.of(), MessageKey.NOTIFY_APPEAL,
                                Arg.s("player", player.getName()),
                                Arg.n("id", id),
                                Arg.n("appeal", appealId),
                                Arg.s("text", text));
                    });
        });
    }
}