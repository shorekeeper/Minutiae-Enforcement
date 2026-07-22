package org.synergyst.minutiae.layout;

import org.synergyst.minutiae.annotation.RawAnnotation;
import org.synergyst.minutiae.time.DurationSpec;

/**
 * A fully resolved, inheritance-flattened layout.
 *
 * <p>All inherited fields have been merged: scalar fields hold the effective
 * value after override, and the annotation array holds the concatenation of
 * ancestor and own annotations in inheritance order. A resolved layout carries
 * no further reference to its parent.
 *
 * <p>Field nullability after resolution:
 * <ul>
 *   <li>{@code rule} may be null for a private base that declares none; for any
 *       invocable layout it is guaranteed non-null and guaranteed to reference a
 *       defined rule.</li>
 *   <li>{@code reason} may be null; consumers substitute the rule description.</li>
 *   <li>{@code duration} may be null for a private base; for any invocable
 *       layout it is guaranteed non-null.</li>
 *   <li>{@code escalation} and {@code annotations} are never null; absence is
 *       represented by a zero-length array.</li>
 * </ul>
 *
 * @param key         the layout key, as invoked via {@code ::key}
 * @param isPrivate   whether the key is private (underscore-prefixed) and thus
 *                    excluded from the invocable registry
 * @param rule        effective rule identifier, or null
 * @param reason      effective display reason, or null
 * @param duration    effective duration, or null
 * @param escalation  effective escalation ladder
 * @param annotations effective annotation set
 */
public record Layout(String key,
                     boolean isPrivate,
                     String rule,
                     String reason,
                     org.synergyst.minutiae.measure.Measure measure,
                     DurationSpec duration,
                     DurationSpec[] escalation,
                     RawAnnotation[] annotations) {
}