package org.synergyst.minutiae.layout;

import org.synergyst.minutiae.annotation.RawAnnotation;
import org.synergyst.minutiae.time.DurationSpec;

import java.util.List;

/**
 * A single raw layout definition as read from configuration, before inheritance
 * resolution.
 *
 * <p>Nullable scalar fields distinguish "absent" (inherit from parent) from
 * "present" (override). The annotation list is never null; an entry-free
 * definition carries an empty list, and annotations are always additive under
 * inheritance rather than overriding.
 *
 * @param key         the layout key
 * @param isPrivate   whether the key is underscore-prefixed
 * @param extendsKey  parent layout key, or null when this definition is a root
 * @param rule        declared rule identifier, or null when inherited
 * @param reason      declared reason, or null when inherited
 * @param duration    declared duration, or null when inherited
 * @param escalation  declared escalation ladder, or null when inherited
 * @param annotations declared annotations, never null
 */
public record LayoutDefinition(String key,
                               boolean isPrivate,
                               String extendsKey,
                               String rule,
                               String reason,
                               org.synergyst.minutiae.measure.Measure measure,
                               DurationSpec duration,
                               DurationSpec[] escalation,
                               List<RawAnnotation> annotations) {
}