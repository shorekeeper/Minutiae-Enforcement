package org.synergyst.minutiae.storage;

/**
 * Outcome of a rule-cache synchronisation.
 *
 * @param added     number of identifiers newly inserted
 * @param changed   number of identifiers whose content hash differed and were
 *                  updated
 * @param removed   number of cached identifiers no longer present in the source
 *                  and deleted
 * @param unchanged number of identifiers already current, left untouched
 */
public record RuleSyncResult(int added, int changed, int removed, int unchanged) {

    /** Reports whether the synchronisation altered any rows. */
    public boolean mutated() {
        return added > 0 || changed > 0 || removed > 0;
    }
}