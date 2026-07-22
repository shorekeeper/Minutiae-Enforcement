package org.synergyst.minutiae.annotation;

/**
 * Validates the parameters of a parsed annotation token against the schema of
 * its specification.
 *
 * <p>Validation is a pure function of the token: it inspects positional and
 * named parameters only and produces no side effects. A validator does not
 * consider negation, scope, permissions, or inter-annotation relationships;
 * those concerns are enforced elsewhere.
 */
@FunctionalInterface
public interface ParamValidator {

    /**
     * Validates the parameters of a token.
     *
     * @param annotation the parsed token
     * @return {@code null} if the parameters satisfy the schema, otherwise a
     *         concise diagnostic describing the violation
     */
    String validate(RawAnnotation annotation);
}