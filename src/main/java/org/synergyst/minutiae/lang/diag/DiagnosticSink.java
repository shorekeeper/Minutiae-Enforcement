package org.synergyst.minutiae.lang.diag;

/**
 * Consumer of compiler diagnostics.
 *
 * <p>Implementations must tolerate reporting from any compilation phase and
 * must not throw. A sink is confined to the thread performing compilation.
 */
public interface DiagnosticSink {

    /**
     * Accepts one diagnostic.
     *
     * @param diagnostic the diagnostic; never null
     */
    void report(Diagnostic diagnostic);

    /** Reports an error with the given code and position. */
    default void error(final String code, final int line, final int column, final String message) {
        report(new Diagnostic(Severity.ERROR, code, line, column, message));
    }

    /** Reports a warning with the given code and position. */
    default void warning(final String code, final int line, final int column, final String message) {
        report(new Diagnostic(Severity.WARNING, code, line, column, message));
    }
}