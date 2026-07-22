package org.synergyst.minutiae.web;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Synthetic command sender through which panel actions are attributed and
 * dispatched.
 *
 * <p>A panel action is executed through the identical resolver, executor, and
 * lift path as a manual command, so it undergoes the same validation and audit.
 * This sender supplies the attribution name of the web user and a permission
 * predicate derived from the user's role, and it captures any user-facing reply
 * the engine produces as plain text. It is never a live player; entity and
 * attachment operations are inert.
 */
public final class WebCommandSender implements CommandSender {

    private final JavaPlugin plugin;
    private final String name;

    private final List<String> replies = Collections.synchronizedList(new java.util.ArrayList<>());
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

    private final java.util.function.Predicate<String> permissions;

    public WebCommandSender(final JavaPlugin plugin, final String name,
                            final java.util.function.Predicate<String> permissions) {
        this.plugin = plugin;
        this.name = name;
        this.permissions = permissions;
    }

    @Override
    public boolean isPermissionSet(final String node) {
        return permissions.test(node);
    }

    @Override
    public boolean isPermissionSet(final Permission perm) {
        return permissions.test(perm.getName());
    }

    @Override
    public boolean hasPermission(final String node) {
        return permissions.test(node);
    }

    @Override
    public boolean hasPermission(final Permission perm) {
        return permissions.test(perm.getName());
    }

    @Override
    public boolean isOp() {
        return false;
    }

    @Override
    public net.kyori.adventure.text.Component name() {
        return net.kyori.adventure.text.Component.text(name);
    }

    /** Returns the captured reply lines. */
    public List<String> replies() {
        return replies;
    }

    @Override
    public void sendMessage(final Component message) {
        replies.add(plain.serialize(message));
    }

    @Override
    public void sendMessage(final String message) {
        replies.add(message);
    }

    @Override
    public void sendMessage(final String... messages) {
        for (final String m : messages) {
            replies.add(m);
        }
    }

    @Override
    public void sendMessage(final UUID sender, final String message) {
        replies.add(message);
    }

    @Override
    public void sendMessage(final UUID sender, final String... messages) {
        for (final String m : messages) {
            replies.add(m);
        }
    }

    @Override
    public Server getServer() {
        return plugin.getServer();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Spigot spigot() {
        return new Spigot();
    }

    @Override
    public PermissionAttachment addAttachment(final Plugin p, final String node, final boolean value) {
        return null;
    }

    @Override
    public PermissionAttachment addAttachment(final Plugin p) {
        return null;
    }

    @Override
    public PermissionAttachment addAttachment(final Plugin p, final String node, final boolean value,
                                              final int ticks) {
        return null;
    }

    @Override
    public PermissionAttachment addAttachment(final Plugin p, final int ticks) {
        return null;
    }

    @Override
    public void removeAttachment(final PermissionAttachment attachment) {
    }

    @Override
    public void recalculatePermissions() {
    }

    @Override
    public Set<PermissionAttachmentInfo> getEffectivePermissions() {
        return Set.of();
    }

    @Override
    public void setOp(final boolean value) {
    }
}