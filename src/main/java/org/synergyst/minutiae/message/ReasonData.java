package org.synergyst.minutiae.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;

/**
 * Data backing the rich {@code reason} placeholder.
 *
 * <p>The inline representation is the reason text abbreviated to the configured
 * length; hovering it reveals a card composed from the {@code reason.display}
 * and {@code reason.card} templates, so the full reason and its context remain
 * accessible without cluttering the chat line. All fields are plain text and are
 * never interpreted as formatting.
 *
 * @param reason   full reason text, or null
 * @param measure  measure name
 * @param rule     primary rule identifier, or a dash when none
 * @param duration formatted duration
 * @param staff    attributed staff name
 * @param when     formatted issue timestamp
 */
public record ReasonData(String reason,
                         String measure,
                         String rule,
                         String duration,
                         String staff,
                         String when) {

    /**
     * Renders the inline reason component with its hover card.
     *
     * @param service   the message service
     * @param localeTag the recipient locale tag, or null
     * @return the rendered component
     */
    public Component render(final MessageService service, final String localeTag) {
        final String shortText = service.abbreviate(reason);
        final Component visible = service.render(localeTag, MessageKey.REASON_DISPLAY,
                Arg.s("short", shortText));
        final Component card = service.render(localeTag, MessageKey.REASON_CARD,
                Arg.s("reason", reason == null ? "-" : reason),
                Arg.measure(measure),
                Arg.s("rule", rule),
                Arg.s("duration", duration),
                Arg.s("staff", staff),
                Arg.s("when", when));
        return visible.hoverEvent(HoverEvent.showText(card));
    }
}