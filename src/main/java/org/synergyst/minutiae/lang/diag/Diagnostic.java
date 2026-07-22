package org.synergyst.minutiae.lang.diag;

/**
 * A single positioned compiler diagnostic.
 *
 * <p>{@code code} is a stable machine-readable identifier drawn from the code
 * table of the language package documentation. {@code line} and {@code column}
 * are one-based and address the first character of the offending construct.
 * Instances are immutable value objects.
 *
 * @param severity severity class
 * @param code     stable diagnostic code
 * @param line     one-based source line
 * @param column   one-based source column
 * @param message  human-readable description; never null
 */
public record Diagnostic(Severity severity, String code, int line, int column, String message) {

    /** Renders the diagnostic in the canonical {@code CODE line:col message} form. */
    public String render() {
        return code + " " + line + ":" + column + ": " + message;
    }
}