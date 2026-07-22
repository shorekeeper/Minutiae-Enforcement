package org.synergyst.minutiae.annotation;

import org.bukkit.configuration.ConfigurationSection;
import org.synergyst.minutiae.log.KernelLogger;

import java.util.List;
import java.util.Map;

/**
 * Precomputed relationship matrix over the annotation catalogue.
 *
 * <p>Relationships are stored as ordinal-indexed {@code long} bitmasks, one word
 * per annotation. The {@code closure} row of an annotation is its transitive
 * implication set including itself, computed once at construction so that
 * expanding an arbitrary annotation set to its implied superset is a single
 * linear pass with no fixpoint iteration at query time. The {@code conflicts}
 * matrix is symmetric and self-exclusive. The {@code requires} matrix holds
 * direct requirements, evaluated against an already-expanded set.
 *
 * <p>All query methods operate on and return a {@code long} membership mask,
 * where bit {@code k} denotes the annotation with ordinal {@code k}.
 */
public final class AnnotationMatrix {

    /** An unordered conflicting pair, by ordinal. */
    public record Conflict(int left, int right) {
    }

    /** A missing requirement: {@code requirer} needs {@code required}. */
    public record Missing(int requirer, int required) {
    }

    private final long[] closure;
    private final long[] conflicts;
    private final long[] requires;

    private AnnotationMatrix(final long[] closure, final long[] conflicts, final long[] requires) {
        this.closure = closure;
        this.conflicts = conflicts;
        this.requires = requires;
    }

    /**
     * Builds a matrix from the catalogue and the optional configuration section.
     *
     * <p>Unknown annotation names are reported and skipped. Implication chains
     * that produce an internal conflict are reported; the offending edges remain
     * in place, deferring rejection to set-level validation.
     *
     * @param catalog the annotation catalogue
     * @param section the {@code annotations} configuration section, or null
     * @param log     diagnostic logger
     * @return the constructed matrix
     */
    public static AnnotationMatrix build(final AnnotationCatalog catalog,
                                         final ConfigurationSection section,
                                         final KernelLogger log) {
        final int n = catalog.size();
        final long[] directImplies = new long[n];
        final long[] conflicts = new long[n];
        final long[] requires = new long[n];

        if (section != null) {
            readImplies(catalog, section, log, directImplies);
            readRequires(catalog, section, log, requires);
            readConflicts(catalog, section, log, conflicts);
        }

        final long[] closure = transitiveClosure(directImplies, n);
        final AnnotationMatrix matrix = new AnnotationMatrix(closure, conflicts, requires);
        matrix.verifyConsistency(catalog, log);
        return matrix;
    }

    private static void readImplies(final AnnotationCatalog catalog,
                                    final ConfigurationSection section,
                                    final KernelLogger log,
                                    final long[] directImplies) {
        final ConfigurationSection impl = section.getConfigurationSection("implies");
        if (impl == null) {
            return;
        }
        for (final String from : impl.getKeys(false)) {
            final AnnotationSpec src = catalog.byName(from);
            if (src == null) {
                log.warn("annotations", "implies: unknown annotation '%s'", from);
                continue;
            }
            for (final String to : impl.getStringList(from)) {
                final AnnotationSpec dst = catalog.byName(to);
                if (dst == null) {
                    log.warn("annotations", "implies[%s]: unknown target '%s'", from, to);
                    continue;
                }
                directImplies[src.ordinal()] |= dst.bit();
            }
        }
    }

    private static void readRequires(final AnnotationCatalog catalog,
                                     final ConfigurationSection section,
                                     final KernelLogger log,
                                     final long[] requires) {
        final ConfigurationSection req = section.getConfigurationSection("requires");
        if (req == null) {
            return;
        }
        for (final String from : req.getKeys(false)) {
            final AnnotationSpec src = catalog.byName(from);
            if (src == null) {
                log.warn("annotations", "requires: unknown annotation '%s'", from);
                continue;
            }
            for (final String to : req.getStringList(from)) {
                final AnnotationSpec dst = catalog.byName(to);
                if (dst == null) {
                    log.warn("annotations", "requires[%s]: unknown target '%s'", from, to);
                    continue;
                }
                requires[src.ordinal()] |= dst.bit();
            }
        }
    }

    private static void readConflicts(final AnnotationCatalog catalog,
                                      final ConfigurationSection section,
                                      final KernelLogger log,
                                      final long[] conflicts) {
        final List<?> pairs = section.getList("conflicts");
        if (pairs == null) {
            return;
        }
        for (final Object raw : pairs) {
            if (!(raw instanceof List<?> pair) || pair.size() != 2) {
                log.warn("annotations", "conflicts: entry is not a pair: %s", raw);
                continue;
            }
            final AnnotationSpec a = catalog.byName(String.valueOf(pair.get(0)));
            final AnnotationSpec b = catalog.byName(String.valueOf(pair.get(1)));
            if (a == null || b == null) {
                log.warn("annotations", "conflicts: unknown annotation in pair %s", pair);
                continue;
            }
            if (a.ordinal() == b.ordinal()) {
                log.warn("annotations", "conflicts: annotation '%s' cannot conflict with itself", a.name());
                continue;
            }
            conflicts[a.ordinal()] |= b.bit();
            conflicts[b.ordinal()] |= a.bit();
        }
    }

    private static long[] transitiveClosure(final long[] directImplies, final int n) {
        final long[] c = new long[n];
        for (int i = 0; i < n; i++) {
            c[i] = directImplies[i] | (1L << i);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < n; i++) {
                final long before = c[i];
                long bits = c[i];
                while (bits != 0) {
                    final int j = Long.numberOfTrailingZeros(bits);
                    bits &= bits - 1;
                    c[i] |= c[j];
                }
                if (c[i] != before) {
                    changed = true;
                }
            }
        }
        return c;
    }

    private void verifyConsistency(final AnnotationCatalog catalog, final KernelLogger log) {
        for (int i = 0; i < closure.length; i++) {
            final long expanded = implyClosure(1L << i);
            final Conflict c = firstConflict(expanded);
            if (c != null) {
                log.error("annotations", "implication of '%s' yields conflict '%s' <> '%s'",
                        catalog.byOrdinal(i).name(),
                        catalog.byOrdinal(c.left()).name(),
                        catalog.byOrdinal(c.right()).name());
            }
        }
    }

    /**
     * Expands a membership mask to include all transitively implied annotations.
     *
     * @param mask the input membership mask
     * @return the expanded mask, a superset of the input
     */
    public long implyClosure(final long mask) {
        long out = mask;
        long bits = mask;
        while (bits != 0) {
            final int i = Long.numberOfTrailingZeros(bits);
            bits &= bits - 1;
            out |= closure[i];
        }
        return out;
    }

    /**
     * Finds the first conflicting pair within a membership mask.
     *
     * @param mask the membership mask
     * @return a conflicting pair, or {@code null} if the mask is conflict-free
     */
    public Conflict firstConflict(final long mask) {
        long bits = mask;
        while (bits != 0) {
            final int i = Long.numberOfTrailingZeros(bits);
            bits &= bits - 1;
            final long hit = conflicts[i] & mask;
            if (hit != 0) {
                return new Conflict(i, Long.numberOfTrailingZeros(hit));
            }
        }
        return null;
    }

    /**
     * Finds the first unsatisfied requirement within a membership mask.
     *
     * @param mask the membership mask, expected to be implication-expanded
     * @return a missing requirement, or {@code null} if all are satisfied
     */
    public Missing firstMissing(final long mask) {
        long bits = mask;
        while (bits != 0) {
            final int i = Long.numberOfTrailingZeros(bits);
            bits &= bits - 1;
            final long missing = requires[i] & ~mask;
            if (missing != 0) {
                return new Missing(i, Long.numberOfTrailingZeros(missing));
            }
        }
        return null;
    }

    /** Returns the direct implication-closure row for an ordinal. */
    public long closureOf(final int ordinal) {
        return closure[ordinal];
    }

    /** Returns the conflict row for an ordinal. */
    public long conflictsOf(final int ordinal) {
        return conflicts[ordinal];
    }

    /** Returns the requirement row for an ordinal. */
    public long requiresOf(final int ordinal) {
        return requires[ordinal];
    }
}