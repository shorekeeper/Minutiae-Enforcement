package org.synergyst.minutiae.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * Factory methods for MiniMessage placeholder resolvers.
 *
 * <p>String and numeric values are inserted as unparsed text: a value is never
 * itself interpreted as MiniMessage markup, so a player name, reason, or other
 * user-supplied fragment cannot inject formatting or structure into a message.
 * Component values are inserted verbatim as a pre-built component.
 */
public final class Ph {

    private Ph() {
    }

    /**
     * Creates a resolver inserting a string value as unparsed text.
     *
     * @param name  placeholder name, without angle brackets
     * @param value the value; a null value is inserted as the empty string
     * @return the resolver
     */
    public static TagResolver s(final String name, final String value) {
        return Placeholder.unparsed(name, value == null ? "" : value);
    }

    /**
     * Creates a resolver inserting a long value as unparsed text.
     *
     * @param name  placeholder name
     * @param value the value
     * @return the resolver
     */
    public static TagResolver n(final String name, final long value) {
        return Placeholder.unparsed(name, Long.toString(value));
    }

    /**
     * Creates a resolver inserting a pre-built component.
     *
     * @param name  placeholder name
     * @param value the component
     * @return the resolver
     */
    public static TagResolver c(final String name, final Component value) {
        return Placeholder.component(name, value);
    }
}