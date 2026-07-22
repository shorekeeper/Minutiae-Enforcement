package org.synergyst.minutiae.engine;

import java.util.Map;
import java.util.UUID;

/**
 * Backend-agnostic snapshot of a triggering event.
 *
 * <p>Facts carry the event kind, the subject account, its display name, and a
 * flat map of typed fields keyed by name. Field values are limited to
 * {@link Long}, {@link Double}, {@link Boolean}, and {@link String}. This
 * representation is deliberately free of any platform type so that the guard
 * evaluator, the dispatch engine, and the simulation command operate on the
 * identical structure a live listener produces, enabling exhaustive testing and
 * dry-run simulation without a live server.
 *
 * @param kind        the event kind
 * @param subject     the subject account, or null when unknown
 * @param subjectName the subject display name
 * @param fields      typed event fields, never null
 */
public record EventFacts(EventKind kind, UUID subject, String subjectName, Map<String, Object> fields) {

    /**
     * Returns a field value.
     *
     * @param name the field name
     * @return the value, or null when absent
     */
    public Object field(final String name) {
        return fields.get(name);
    }

    /**
     * Constructs facts with a single field.
     *
     * @param kind    event kind
     * @param subject subject account
     * @param name    subject display name
     * @param key     field key
     * @param value   field value
     * @return the facts
     */
    public static EventFacts of(final EventKind kind, final UUID subject, final String name,
                                final String key, final Object value) {
        return new EventFacts(kind, subject, name, Map.of(key, value));
    }
}