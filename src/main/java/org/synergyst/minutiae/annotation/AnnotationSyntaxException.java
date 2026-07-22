package org.synergyst.minutiae.annotation;

/**
 * Raised when an annotation token violates the annotation surface grammar.
 *
 * <p>The message identifies the specific violation and, where applicable, the
 * character offset at which it was detected.
 */
public final class AnnotationSyntaxException extends RuntimeException {

    public AnnotationSyntaxException(final String message) {
        super(message);
    }
}