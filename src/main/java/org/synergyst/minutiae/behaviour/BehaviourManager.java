package org.synergyst.minutiae.behaviour;

import org.synergyst.minutiae.lifecycle.LifecycleComponent;
import org.synergyst.minutiae.log.KernelLogger;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative in-memory store of per-player behavioural state.
 *
 * <p>State is held in a concurrent map keyed by player UUID, each value an
 * immutable {@link BehaviourRecord}. Mutations replace the record atomically via
 * {@link ConcurrentHashMap#compute}; reads observe a consistent snapshot without
 * locking. The map holds this component's only mutable state; the manager holds
 * no Bukkit references and performs no I/O, keeping it usable from any thread.
 *
 * <p>The {@link #isEmpty()} query is the guard hot-path listeners consult before
 * any per-player lookup: on a server with no active behavioural sanctions the
 * map is empty and listeners return immediately.
 *
 * <p>Expiry is evaluated lazily by {@link BehaviourRecord}; expired constraints
 * are reported inactive without eager rewriting. Records are removed wholesale
 * on player disconnect.
 */
public final class BehaviourManager implements LifecycleComponent {

    private final KernelLogger log;
    private final ConcurrentHashMap<UUID, BehaviourRecord> states = new ConcurrentHashMap<>(64);

    public BehaviourManager(final KernelLogger log) {
        this.log = log;
    }

    @Override
    public String tag() {
        return "behaviour";
    }

    @Override
    public void boot() {
        log.trace("behaviour", "state store initialised");
    }

    @Override
    public void shutdown() {
        states.clear();
    }

    /**
     * Applies a constraint mask to a player, merging with any existing state.
     *
     * @param uuid      the player UUID
     * @param addMask   constraints to add
     * @param expiresAt sanction expiry in epoch milliseconds, or zero for a
     *                  permanent constraint
     * @param reason    reason associated with the constraints
     */
    public void apply(final UUID uuid, final long addMask, final long expiresAt, final String reason) {
        if (addMask == 0L) {
            return;
        }
        final long effective = expiresAt == 0L ? BehaviourRecord.PERMANENT : expiresAt;
        states.compute(uuid, (key, existing) -> {
            final BehaviourRecord base = existing == null ? BehaviourRecord.empty() : existing;
            return base.withApplied(addMask, effective, reason);
        });
    }

    /**
     * Returns the current record for a player.
     *
     * @param uuid the player UUID
     * @return the record, or null when the player has no recorded state
     */
    public BehaviourRecord get(final UUID uuid) {
        return states.get(uuid);
    }

    /**
     * Tests whether a specific constraint is active on a player.
     *
     * @param uuid      the player UUID
     * @param behaviour the constraint
     * @param now       current epoch-millisecond timestamp
     * @return {@code true} when the constraint is present and unexpired
     */
    public boolean has(final UUID uuid, final Behaviour behaviour, final long now) {
        final BehaviourRecord record = states.get(uuid);
        return record != null && record.has(behaviour, now);
    }

    /**
     * Tests whether a player is hidden from others, that is, ghosted or
     * shadowed.
     *
     * @param uuid the player UUID
     * @param now  current epoch-millisecond timestamp
     * @return {@code true} when a concealment constraint is active
     */
    public boolean isConcealed(final UUID uuid, final long now) {
        final BehaviourRecord record = states.get(uuid);
        return record != null
                && (record.has(Behaviour.GHOSTED, now) || record.has(Behaviour.SHADOWED, now));
    }


    /**
     * Removes records whose every constraint has expired, freeing memory for
     * players no longer under any behavioural sanction. A record retaining at
     * least one unexpired constraint is left intact.
     *
     * @param now current epoch-millisecond timestamp
     * @return the number of records removed
     */
    public int sweepExpired(final long now) {
        int removed = 0;
        final var iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            if (!iterator.next().getValue().any(now)) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    /**
     * Removes all recorded state for a player.
     *
     * @param uuid the player UUID
     */
    public void remove(final UUID uuid) {
        states.remove(uuid);
    }

    /**
     * Reports whether any player currently has recorded state.
     *
     * @return {@code true} when the store is empty
     */
    public boolean isEmpty() {
        return states.isEmpty();
    }
}