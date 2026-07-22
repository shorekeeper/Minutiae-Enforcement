package org.synergyst.minutiae.fingerprint;

import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.storage.SignalFreqRow;
import org.synergyst.minutiae.storage.Storage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Storage-backed {@link FrequencyOracle} serving from a periodically-rebuilt
 * in-memory snapshot.
 *
 * <p>The oracle answers collision statistics from an immutable snapshot rather
 * than a per-query round trip, so that the scoring path performs no I/O and no
 * blocking. A snapshot binds a value-keyed count map to the corpus size recorded
 * at the time it was built. Snapshots are published by wholesale reference
 * assignment to a volatile field; a scoring thread observes either the previous
 * snapshot in full or the next in full, never a partial state.
 *
 * <p>The key of the count map is the signal type code and value joined by a NUL
 * separator, a character excluded from every signal value produced by the
 * collector, so distinct {@code (type, value)} pairs never collide. A lookup
 * that misses the map returns a count of zero, which the scoring model treats as
 * an unseen value and estimates at the minimum non-match likelihood.
 *
 * <p>Rebuilding performs a bounded load of the frequency aggregate and the
 * corpus size on the asynchronous storage scheduler; it must not be invoked on
 * the server main thread. The initial snapshot is empty with a corpus size of
 * one, so the oracle is usable before its first rebuild completes.
 */
public final class StorageFrequencyOracle implements FrequencyOracle {

    private static final char SEP = '\u0000';
    private static final int LOAD_LIMIT = 200_000;

    private record Snapshot(Map<String, Long> counts, long total, long builtAt) {
    }

    private final KernelLogger log;
    private final Storage storage;
    private volatile Snapshot snapshot = new Snapshot(Map.of(), 1L, 0L);

    public StorageFrequencyOracle(final KernelLogger log, final Storage storage) {
        this.log = log;
        this.storage = storage;
    }

    /**
     * Rebuilds the aggregate and reloads the snapshot, blocking on the storage
     * futures. Intended for invocation from the asynchronous maintenance path.
     *
     * @param now the rebuild timestamp in epoch milliseconds
     */
    public void rebuild(final long now) {
        storage.refreshFrequencyAggregate().join();
        final List<SignalFreqRow> rows = storage.loadFrequencies(LOAD_LIMIT).join();
        final long total = storage.corpusSize().join();
        final Map<String, Long> counts = new HashMap<>(Math.max(16, rows.size() * 2));
        for (final SignalFreqRow r : rows) {
            counts.put(key(r.type(), r.value()), r.accounts());
        }
        this.snapshot = new Snapshot(counts, Math.max(total, 1L), now);
        log.trace("fingerprint", "frequency oracle rebuilt: %d value(s), corpus %d",
                counts.size(), total);
    }

    @Override
    public long accountsBearing(final int type, final String value) {
        final Long v = snapshot.counts().get(key(type, value));
        return v == null ? 0L : v;
    }

    @Override
    public long totalAccounts() {
        return snapshot.total();
    }

    /** Returns the epoch-millisecond time the current snapshot was built. */
    public long builtAt() {
        return snapshot.builtAt();
    }

    private static String key(final int type, final String value) {
        return type + SEP + value;
    }
}