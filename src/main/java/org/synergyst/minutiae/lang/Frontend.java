package org.synergyst.minutiae.lang;

import org.synergyst.minutiae.lang.ast.Decl;
import org.synergyst.minutiae.lang.diag.Diagnostic;
import org.synergyst.minutiae.lang.diag.Diagnostics;
import org.synergyst.minutiae.lang.lex.Lexer;
import org.synergyst.minutiae.lang.parse.Parser;

import java.util.List;

/**
 * Entry point of the syntactic frontend: lexing and parsing of one source.
 *
 * <p>The frontend never throws for malformed input; every violation is
 * reported as a positioned diagnostic, and well-formed sibling declarations
 * are retained. The result is immutable and safe to hand across threads.
 */
public final class Frontend {

    /**
     * Outcome of the syntactic frontend for one source.
     *
     * @param origin      source identifier used in operator-facing reporting
     * @param declarations successfully parsed declarations, in source order
     * @param diagnostics diagnostics in report order
     * @param ok          whether no error-severity diagnostic was reported
     */
    public record Result(String origin, List<Decl> declarations,
                         List<Diagnostic> diagnostics, boolean ok) {
    }

    private Frontend() {
    }

    /**
     * Lexes and parses one source.
     *
     * @param origin source identifier, typically a file name
     * @param text   source text
     * @return the frontend result
     */
    public static Result run(final String origin, final String text) {
        final Diagnostics diags = new Diagnostics();
        final var tokens = Lexer.lex(text, diags);
        final List<Decl> decls = Parser.parse(tokens, diags);
        return new Result(origin, List.copyOf(decls), diags.all(), !diags.hasErrors());
    }
}