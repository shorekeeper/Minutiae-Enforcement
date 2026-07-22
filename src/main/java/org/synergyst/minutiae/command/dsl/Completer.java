package org.synergyst.minutiae.command.dsl;

import java.util.List;

/**
 * Produces completion candidates for the trailing token of an argument.
 *
 * <p>Candidates are full-token replacements. The framework filters them by
 * case-insensitive prefix against the partial token and anchors them at the
 * token's start offset, so a completer may return the entire candidate set
 * without pre-filtering.
 */
@FunctionalInterface
public interface Completer {

    /**
     * Computes candidates for the current partial token.
     *
     * @param ctx the completion context
     * @return candidate replacement tokens
     */
    List<String> complete(CompleteCtx ctx);
}