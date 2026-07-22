package org.synergyst.minutiae.lang;

import org.synergyst.minutiae.lang.ast.Decl;
import org.synergyst.minutiae.lang.diag.Diagnostics;
import org.synergyst.minutiae.lang.eval.Evaluator;
import org.synergyst.minutiae.lang.lex.Lexer;
import org.synergyst.minutiae.lang.parse.Parser;
import org.synergyst.minutiae.lang.sem.ElabResult;
import org.synergyst.minutiae.lang.sem.Elaborator;

import java.util.List;

/**
 * End-to-end compiler of the definition language: lexing, parsing,
 * elaboration, and compile-time evaluation of one source into a
 * {@link CompiledUnit}.
 *
 * <p>All phases share one diagnostic collection. Lexical and syntactic
 * violations do not prevent elaboration of the declarations that parsed;
 * elaboration failures do not prevent elaboration of sibling declarations.
 * Evaluation, whose artifacts arm live enforcement, runs only over a unit
 * whose earlier phases produced no error, so a unit is either fully
 * normalised or rejected whole.
 *
 * <p>Compilation is confined to the calling thread and performs no I/O; the
 * caller supplies source text and owns file access.
 */
public final class Compiler {

    private Compiler() {
    }

    /**
     * Compiles one source.
     *
     * @param origin source identifier, typically a file name
     * @param text   source text
     * @return the compiled unit
     */
    public static CompiledUnit compile(final String origin, final String text) {
        final Diagnostics diags = new Diagnostics();

        final var tokens = Lexer.lex(text, diags);
        final List<Decl> decls = Parser.parse(tokens, diags);
        final ElabResult elab = Elaborator.run(decls, diags);

        if (diags.hasErrors()) {
            return CompiledUnit.failed(origin, diags.all());
        }
        final Evaluator.Output out = Evaluator.run(elab, diags);
        if (diags.hasErrors()) {
            return CompiledUnit.failed(origin, diags.all());
        }
        return new CompiledUnit(origin, out.constants(), out.layouts(), out.rules(),
                out.expandedRules(), out.automata(), diags.all(), true);
    }
}