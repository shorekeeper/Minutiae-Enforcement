package org.synergyst.minutiae.command;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fixed-capacity ring of recently observed sanction and appeal identifiers,
 * feeding synchronous tab completion.
 *
 * <p>Completion suggestions are computed per keystroke, so they must never
 * touch storage. The ring provides the honest synchronous alternative: the
 * identifiers this instance has issued or observed recently, which covers the
 * dominant completion case of acting on an identifier that was just printed to
 * the moderator's screen. The ring is warmed from the most recent persisted
 * rows at boot so completion is useful immediately after a restart.
 *
 * <p>Each ring is a structure of arrays: a primitive identifier array parallel
 * to two halves of the associated subject UUID, avoiding per-entry object
 * allocation. A zero UUID denotes an entry with no recorded subject (a warmed
 * appeal row); such entries are never returned by subject-filtered queries.
 * Recording overwrites the oldest entry when full. Reads materialise a small
 * string list, newest first; identifiers are unique by construction
 * (autoincrement keys), so no deduplication pass is needed.
 *
 * <p>Writers arrive from the main thread (issuance) and scheduler threads
 * (appeal submission, boot warm); readers arrive from the command suggestion
 * path. Each ring is guarded by its own monitor; critical sections are a few
 * array writes, so contention is immaterial.
 */
public final class RecentIds {

    /** Ring capacity per identifier family. */
    public static final int CAPACITY = 64;

    /** Rows fetched per family when warming from storage at boot. */
    public static final int WARM_LIMIT = 32;

    private static final class Ring {
        final long[] ids = new long[CAPACITY];
        final long[] msb = new long[CAPACITY];
        final long[] lsb = new long[CAPACITY];
        int head;
        int count;

        synchronized void record(final long id, final UUID subject) {
            ids[head] = id;
            msb[head] = subject == null ? 0L : subject.getMostSignificantBits();
            lsb[head] = subject == null ? 0L : subject.getLeastSignificantBits();
            head = (head + 1) % CAPACITY;
            if (count < CAPACITY) {
                count++;
            }
        }

        synchronized List<String> all() {
            final List<String> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                final int idx = (head - 1 - i + CAPACITY * 2) % CAPACITY;
                out.add(Long.toString(ids[idx]));
            }
            return out;
        }

        synchronized List<String> of(final UUID subject) {
            if (subject == null) {
                return List.of();
            }
            final long m = subject.getMostSignificantBits();
            final long l = subject.getLeastSignificantBits();
            // A genuine all-zero UUID cannot be distinguished from the "no
            // subject" sentinel and is excluded; Mojang never issues it.
            if (m == 0L && l == 0L) {
                return List.of();
            }
            final List<String> out = new ArrayList<>(4);
            for (int i = 0; i < count; i++) {
                final int idx = (head - 1 - i + CAPACITY * 2) % CAPACITY;
                if (msb[idx] == m && lsb[idx] == l) {
                    out.add(Long.toString(ids[idx]));
                }
            }
            return out;
        }
    }

    private final Ring sanctions = new Ring();
    private final Ring appeals = new Ring();

    /**
     * Records a sanction identifier with its subject account.
     *
     * @param id      the sanction identifier
     * @param subject the subject account, or null when unknown
     */
    public void recordSanction(final long id, final UUID subject) {
        sanctions.record(id, subject);
    }

    /**
     * Records an appeal identifier with its appellant account.
     *
     * @param id        the appeal identifier
     * @param appellant the appellant account, or null when unknown
     */
    public void recordAppeal(final long id, final UUID appellant) {
        appeals.record(id, appellant);
    }

    /** Returns recent sanction identifiers as tokens, newest first. */
    public List<String> sanctionIds() {
        return sanctions.all();
    }

    /**
     * Returns recent sanction identifiers whose subject matches, newest first.
     *
     * @param subject the subject account, or null for none
     * @return the matching identifiers as tokens
     */
    public List<String> sanctionIdsOf(final UUID subject) {
        return sanctions.of(subject);
    }

    /** Returns recent appeal identifiers as tokens, newest first. */
    public List<String> appealIds() {
        return appeals.all();
    }
}