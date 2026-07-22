package org.synergyst.minutiae.message;

import net.kyori.adventure.text.Component;

/**
 * A single template argument binding a placeholder name to a value.
 *
 * <p>An argument is either scalar or rich. A scalar argument supplies plain text
 * that is substituted into the template string prior to legacy deserialisation;
 * its value is sanitised so that user-supplied content cannot introduce colour
 * or formatting codes. A rich argument supplies a {@link RichValue} that is
 * rendered to a component and spliced into the result, enabling hover cards and
 * other interactive elements that the legacy text format cannot express.
 */
public final class Arg {

    private final String name;
    private final String scalar;
    private final RichValue rich;

    private Arg(final String name, final String scalar, final RichValue rich) {
        this.name = name;
        this.scalar = scalar;
        this.rich = rich;
    }

    /**
     * Creates a rich argument rendering a keyed fragment.
     *
     * <p>The fragment is rendered at delivery in each recipient's locale, so a
     * broadcast shows every recipient the fragment from that recipient's own
     * bundle. Intended for closed-set display fragments such as lift kinds and
     * statuses, whose templates carry no placeholders of their own.
     *
     * @param name the placeholder name, without braces
     * @param key  the fragment's message key
     * @return the argument
     */
    public static Arg key(final String name, final MessageKey key) {
        return new Arg(name, null, (service, localeTag) -> service.render(localeTag, key));
    }

    /**
     * Creates a rich argument bound to the {@code duration} placeholder,
     * rendering the duration in each recipient's locale.
     *
     * @param spec the duration, or null for a dash
     * @return the argument
     */
    public static Arg duration(final org.synergyst.minutiae.time.DurationSpec spec) {
        return new Arg("duration", null, (service, localeTag) ->
                net.kyori.adventure.text.Component.text(service.durationText(localeTag, spec)));
    }

    /**
     * Creates a rich argument bound to the {@code rank} placeholder, rendering
     * the rank display name from the dynamic {@code rank.<id>} bundle
     * namespace in each recipient's locale. An id with no bundle entry renders
     * verbatim.
     *
     * @param rankId the operator-defined rank identifier
     * @return the argument
     */
    public static Arg rank(final String rankId) {
        return rank(rankId, null);
    }

    /**
     * Creates a rich argument bound to the {@code rank} placeholder, rendering
     * a grammatical form of the rank display name.
     *
     * <p>Grammatical context matters in inflected locales: a template reading
     * "sanctioned by {rank}" requires a different case than a bare label. The
     * form selects the bundle path {@code rank.<id>.<form>}; when no bundle
     * defines it, resolution falls back to the base {@code rank.<id>} entry,
     * so locales without grammatical case (English) define the base entry only
     * and every form collapses onto it. An id with no entry at all renders
     * verbatim.
     *
     * <p>Form identifiers are template-author vocabulary, not code vocabulary:
     * the convention shipped with the default bundles is {@code by} for the
     * agentive context ("by X", genitive in Russian), but operators may
     * introduce any suffix their templates need without a code change.
     *
     * @param rankId the operator-defined rank identifier
     * @param form   the grammatical form suffix, or null for the base entry
     * @return the argument
     */
    public static Arg rank(final String rankId, final String form) {
        return new Arg("rank", null, (service, localeTag) ->
                service.dynamicChain(localeTag,
                        form == null
                                ? new String[]{"rank." + rankId}
                                : new String[]{"rank." + rankId + "." + form, "rank." + rankId},
                        rankId));
    }

    /**
     * Creates a scalar string argument.
     *
     * @param name  placeholder name, without braces
     * @param value the value; null is treated as the empty string
     * @return the argument
     */
    public static Arg s(final String name, final String value) {
        return new Arg(name, value == null ? "" : value, null);
    }

    /**
     * Creates a scalar numeric argument.
     *
     * @param name  placeholder name
     * @param value the value
     * @return the argument
     */
    public static Arg n(final String name, final long value) {
        return new Arg(name, Long.toString(value), null);
    }

    /**
     * Creates a rich argument bound to the {@code reason} placeholder, rendering
     * the abbreviated reason with a hover card.
     *
     * @param data the reason card data
     * @return the argument
     */
    public static Arg reason(final ReasonData data) {
        return new Arg("reason", null, data::render);
    }

    /**
     * Creates a rich argument bound to the {@code measure} placeholder,
     * rendering the measure's localised display name.
     *
     * <p>Unlike a scalar argument, the rendering is deferred to delivery, so a
     * broadcast or staff notification shows each recipient the name in that
     * recipient's own locale. A measure name that resolves to no known measure
     * is shown verbatim, preserving the fail-safe display of foreign or
     * corrupted persisted values.
     *
     * @param measureName the measure name as persisted or resolved
     * @return the argument
     */
    public static Arg measure(final String measureName) {
        return new Arg("measure", null, (service, localeTag) -> {
            final MessageKey key = MessageKey.measureKey(measureName);
            if (key == null) {
                return Component.text(measureName == null ? "-" : measureName);
            }
            return service.render(localeTag, key);
        });
    }

    /** Returns the placeholder name. */
    public String name() {
        return name;
    }

    /** Returns the scalar value, or null when this argument is rich. */
    public String scalar() {
        return scalar;
    }

    /** Returns the rich value, or null when this argument is scalar. */
    public RichValue rich() {
        return rich;
    }

    /** Reports whether this argument is rich. */
    public boolean isRich() {
        return rich != null;
    }
}