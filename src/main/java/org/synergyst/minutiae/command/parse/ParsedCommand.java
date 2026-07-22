package org.synergyst.minutiae.command.parse;

import org.synergyst.minutiae.annotation.RawAnnotation;

import java.util.List;
import java.util.Map;

/**
 * Syntactic result of parsing a ban command, prior to semantic resolution.
 *
 * <p>Captures the surface structure only: the target token, an optional layout
 * key, the ordered sequence of annotation tokens (including negated tokens), and
 * scalar overrides keyed by name. No field is validated against any registry at
 * this stage; existence, permission, parameter, and relationship checks are the
 * resolver's responsibility.
 *
 * @param target      the target player token; never null
 * @param layoutKey   the layout key from a {@code ::key} token, or null when the
 *                    command names no layout
 * @param annotations annotation tokens in source order, never null
 * @param overrides   scalar overrides in the form {@code key=value}, never null
 */
public record ParsedCommand(String target,
                            String layoutKey,
                            List<RawAnnotation> annotations,
                            Map<String, String> overrides) {
}