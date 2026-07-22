package org.synergyst.minutiae.annotation;

import org.bukkit.configuration.ConfigurationSection;
import org.synergyst.minutiae.lifecycle.LifecycleComponent;
import org.synergyst.minutiae.lifecycle.Reloadable;
import org.synergyst.minutiae.log.KernelLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * Authoritative annotation model: catalogue plus relationship matrix.
 *
 * <p>On boot the built-in catalogue is instantiated and the relationship matrix
 * is compiled from the {@code annotations} configuration section. The registry
 * exposes name resolution, mask construction, and the validation predicates used
 * to accept or reject annotation tokens in both configuration and command
 * contexts.
 *
 * <p>Validation is layered. {@link #validateKnownAndParams(RawAnnotation)}
 * checks that an annotation exists and that its parameters satisfy its schema.
 * {@link #validateForConfig(RawAnnotation)} additionally rejects inline-only
 * annotations and negation, which are not meaningful in static configuration.
 * Permission and cross-annotation relationship checks are performed by the
 * resolver against a sender and a complete set.
 *
 * <p>After boot the registry is immutable and safe for concurrent access.
 */
public final class AnnotationRegistry implements LifecycleComponent, Reloadable {

    private final KernelLogger log;
    private final java.util.function.Supplier<ConfigurationSection> sectionSupplier;

    private AnnotationCatalog catalog;
    private volatile AnnotationMatrix matrix;

    public AnnotationRegistry(final KernelLogger log,
                              final java.util.function.Supplier<ConfigurationSection> sectionSupplier) {
        this.log = log;
        this.sectionSupplier = sectionSupplier;
    }

    @Override
    public String tag() {
        return "annotations";
    }

    @Override
    public void boot() {
        this.catalog = AnnotationCatalog.builtIn();
        log.trace("annotations", "catalogue instantiated with %d entry(ies)", catalog.size());
        this.matrix = AnnotationMatrix.build(catalog, sectionSupplier.get(), log);
        log.info("annotations", "model ready: %d annotation(s), matrix compiled", catalog.size());
    }

    @Override
    public String reloadTag() {
        return "annotations";
    }

    @Override
    public void reload() {
        // The catalogue is code-defined and stable across reloads; only the
        // relationship matrix, sourced from configuration, is rebuilt.
        this.matrix = AnnotationMatrix.build(catalog, sectionSupplier.get(), log);
        log.info("annotations", "matrix recompiled");
    }

    @Override
    public void shutdown() {
        this.catalog = null;
        this.matrix = null;
    }

    /** Returns the annotation catalogue. */
    public AnnotationCatalog catalog() {
        return catalog;
    }

    /** Returns the relationship matrix. */
    public AnnotationMatrix matrix() {
        return matrix;
    }

    /**
     * Validates that an annotation is known and its parameters conform.
     *
     * @param a the token
     * @return {@code null} on success, otherwise a diagnostic
     */
    public String validateKnownAndParams(final RawAnnotation a) {
        final AnnotationSpec spec = catalog.byName(a.name());
        if (spec == null) {
            return "unknown annotation '@" + a.name() + "'";
        }
        final String paramError = spec.validator().validate(a);
        return paramError == null ? null : "@" + a.name() + " " + paramError;
    }

    /**
     * Validates a token for use in static configuration.
     *
     * @param a the token
     * @return {@code null} on success, otherwise a diagnostic
     */
    public String validateForConfig(final RawAnnotation a) {
        if (a.negated()) {
            return "negation '!@" + a.name() + "' is not permitted in configuration";
        }
        final AnnotationSpec spec = catalog.byName(a.name());
        if (spec == null) {
            return "unknown annotation '@" + a.name() + "'";
        }
        if (spec.scope() == AnnotationSpec.Scope.INLINE_ONLY) {
            return "@" + a.name() + " is inline-only and not permitted in configuration";
        }
        final String paramError = spec.validator().validate(a);
        return paramError == null ? null : "@" + a.name() + " " + paramError;
    }

    /**
     * Builds a membership mask from a sequence of tokens, ignoring negation.
     * Tokens naming unknown annotations are skipped.
     *
     * @param annotations the tokens
     * @return the membership mask
     */
    public long maskOf(final RawAnnotation[] annotations) {
        long mask = 0L;
        for (final RawAnnotation a : annotations) {
            final AnnotationSpec spec = catalog.byName(a.name());
            if (spec != null) {
                mask |= spec.bit();
            }
        }
        return mask;
    }

    /**
     * Renders a membership mask as an ordered list of annotation names.
     *
     * @param mask the membership mask
     * @return the names of set annotations, in ascending ordinal order
     */
    public List<String> namesOf(final long mask) {
        final List<String> out = new ArrayList<>(Long.bitCount(mask));
        long bits = mask;
        while (bits != 0) {
            final int i = Long.numberOfTrailingZeros(bits);
            bits &= bits - 1;
            out.add(catalog.byOrdinal(i).name());
        }
        return out;
    }
}