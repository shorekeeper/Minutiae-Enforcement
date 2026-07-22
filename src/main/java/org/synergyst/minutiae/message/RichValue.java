package org.synergyst.minutiae.message;

import net.kyori.adventure.text.Component;

/**
 * A placeholder value that renders to a styled component rather than plain text.
 *
 * <p>Unlike scalar placeholders, which are substituted into the template string
 * before parsing, a rich value is spliced into the rendered component tree and
 * may therefore carry interactive elements such as hover cards. Rendering is
 * deferred so that the value can localise its own content to the recipient.
 */
@FunctionalInterface
public interface RichValue {

    /**
     * Renders this value for a locale.
     *
     * @param service   the message service, for rendering nested templates
     * @param localeTag the recipient locale tag, or null for the default
     * @return the rendered component
     */
    Component render(MessageService service, String localeTag);
}