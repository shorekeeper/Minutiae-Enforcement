package org.synergyst.minutiae.net;

import org.synergyst.minutiae.log.KernelLogger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;

/**
 * Stable identity of one server instance.
 *
 * <p>The identifier attributes sanctions, addresses directives, and keys the
 * per-instance directive cursor. Resolution order: an operator-configured
 * identifier wins; otherwise a previously generated identifier is read from
 * the {@code server-id} file in the data folder; otherwise a fresh
 * identifier is generated and persisted. Persistence guarantees the
 * identifier survives restarts, which the cursor semantics require - a
 * renamed instance would replay or skip directives.
 *
 * @param id the resolved identifier, never blank
 */
public record ServerIdentity(String id) {

    private static final String FILE_NAME = "server-id";

    /**
     * Resolves the instance identity.
     *
     * @param dataFolder the plugin data folder
     * @param configured the operator-configured identifier, possibly blank
     * @param log        diagnostic logger
     * @return the resolved identity
     */
    public static ServerIdentity resolve(final File dataFolder, final String configured,
                                         final KernelLogger log) {
        if (!configured.isBlank()) {
            return new ServerIdentity(configured);
        }
        final File file = new File(dataFolder, FILE_NAME);
        if (file.isFile()) {
            try {
                final String stored = Files.readString(file.toPath(),
                        StandardCharsets.UTF_8).trim();
                if (!stored.isBlank()) {
                    return new ServerIdentity(stored);
                }
            } catch (final IOException e) {
                log.warn("network", "failed to read '%s': %s; generating a fresh identifier",
                        FILE_NAME, e.getMessage());
            }
        }
        final String generated = generate();
        try {
            Files.writeString(file.toPath(), generated, StandardCharsets.UTF_8);
            log.info("network", "generated server identifier '%s'", generated);
        } catch (final IOException e) {
            // A non-persisted identifier still functions for this session; the
            // consequence is a cursor reset on restart, which the at-least-once
            // idempotent handlers absorb.
            log.warn("network", "failed to persist '%s': %s; identifier is session-scoped",
                    FILE_NAME, e.getMessage());
        }
        return new ServerIdentity(generated);
    }

    private static String generate() {
        final byte[] raw = new byte[4];
        new SecureRandom().nextBytes(raw);
        final StringBuilder sb = new StringBuilder(12).append("srv-");
        for (final byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}