package org.synergyst.minutiae.lang.diag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collecting {@link DiagnosticSink}.
 *
 * <p>Diagnostics are retained in report order. The error flag is monotonic:
 * once an error is reported the collection can never again claim success.
 * The instance is single-threaded and single-use per compilation.
 */
public final class Diagnostics implements DiagnosticSink {

    private final List<Diagnostic> all = new ArrayList<>(16);
    private boolean errors;

    @Override
    public void report(final Diagnostic diagnostic) {
        all.add(diagnostic);
        if (diagnostic.severity() == Severity.ERROR) {
            errors = true;
        }
    }

    /** Returns the diagnostics in report order, read-only. */
    public List<Diagnostic> all() {
        return Collections.unmodifiableList(all);
    }

    /** Reports whether at least one error has been recorded. */
    public boolean hasErrors() {
        return errors;
    }
}