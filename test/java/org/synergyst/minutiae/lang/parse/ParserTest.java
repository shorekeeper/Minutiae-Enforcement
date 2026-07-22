package org.synergyst.minutiae.lang.parse;

import org.junit.jupiter.api.Test;
import org.synergyst.minutiae.lang.ast.Decl;
import org.synergyst.minutiae.lang.ast.Expr;
import org.synergyst.minutiae.lang.diag.Diagnostics;
import org.synergyst.minutiae.lang.lex.Lexer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Declaration structure, expression disambiguation, and recovery of the
 * parser.
 */
final class ParserTest {

    private static List<Decl> parse(final String src, final Diagnostics diags) {
        return Parser.parse(Lexer.lex(src, diags), diags);
    }

    private static List<Decl> parseOk(final String src) {
        final Diagnostics diags = new Diagnostics();
        final List<Decl> decls = parse(src, diags);
        assertFalse(diags.hasErrors(), () -> diags.all().toString());
        return decls;
    }

    @Test
    void parsesRuleAsSingleImplication() {
        final List<Decl> decls = parseOk("""
                rule<Chat> r {
                    condition {
                        trigger = Trigger::Atomic {};
                        guard = (e) -> true;
                    }
                    => verdict = (e) -> e;
                }
                """);
        final Decl.Rule rule = (Decl.Rule) decls.get(0);
        assertEquals(List.of("Chat"), rule.events());
        assertNotNull(rule.trigger());
        assertNotNull(rule.guard());
        assertNotNull(rule.verdict());
    }

    @Test
    void instantiationIsSpeculativeAndComparisonSurvives() {
        final List<Decl> decls = parseOk("""
                const Bool a = x < y;
                const Int b = f<1, 2>(z);
                """);
        final Decl.Const cmp = (Decl.Const) decls.get(0);
        assertInstanceOf(Expr.Binary.class, cmp.init());
        final Decl.Const inst = (Decl.Const) decls.get(1);
        final Expr.Call call = (Expr.Call) inst.init();
        assertInstanceOf(Expr.Instantiate.class, call.callee());
    }

    @Test
    void lambdaAndGroupingDisambiguate() {
        final List<Decl> decls = parseOk("""
                const Int a = (x) -> x;
                const Int b = (1 + 2);
                """);
        assertInstanceOf(Expr.Lambda.class, ((Decl.Const) decls.get(0)).init());
        assertInstanceOf(Expr.Binary.class, ((Decl.Const) decls.get(1)).init());
    }

    @Test
    void braceOpensListOrAnonymousRecord() {
        final List<Decl> decls = parseOk("""
                const Int a = { 1, 2, };
                const Int b = { .x = 1, .y = 2 };
                """);
        assertInstanceOf(Expr.ListLit.class, ((Decl.Const) decls.get(0)).init());
        final Expr.RecordLit rec = (Expr.RecordLit) ((Decl.Const) decls.get(1)).init();
        assertNull(rec.target());
        assertEquals(2, rec.fields().size());
    }

    @Test
    void fusedGreaterEqualsSplitsAtSpecialisationBoundary() {
        final List<Decl> decls = parseOk("""
                template<Int N>
                verdict t = (e) -> e;
                template<> verdict t<_>= (e) -> e;
                """);
        final Decl.TemplateSpec spec = (Decl.TemplateSpec) decls.get(1);
        assertInstanceOf(Decl.TemplateSpec.Any.class, spec.args().get(0));
    }

    @Test
    void templateCarriesParamsAndConstraints() {
        final Decl.Template tpl = (Decl.Template) parseOk("""
                template<Measure M, Duration D>
                where {
                    D <= 30d;
                }
                verdict ladder = (e) -> e;
                """).get(0);
        assertEquals(2, tpl.params().size());
        assertEquals("M", tpl.params().get(0).name());
        assertEquals(1, tpl.constraints().size());
        assertEquals(Decl.TemplateKind.VERDICT, tpl.kind());
    }

    @Test
    void duplicateFieldInitializerIsReported() {
        final Diagnostics diags = new Diagnostics();
        parse("const Int a = { .x = 1, .x = 2 };", diags);
        assertTrue(TestCodes.of(diags).contains("P010"));
    }

    @Test
    void expandRequiresApplication() {
        final Diagnostics diags = new Diagnostics();
        parse("expand t;", diags);
        assertTrue(TestCodes.of(diags).contains("P007"));
    }

    @Test
    void matchArmsCoverPatternForms() {
        final Expr.Match m = (Expr.Match) ((Decl.Const) parseOk("""
                const Int a = match (x) {
                    1 => 1,
                    2..5 => 2,
                    Measure::MUTE => 3,
                    _ => 4,
                };
                """).get(0)).init();
        assertEquals(4, m.arms().size());
    }

    @Test
    void recoveryRetainsSiblingDeclarations() {
        final Diagnostics diags = new Diagnostics();
        final List<Decl> decls = parse("""
                const Int = 5;
                const Int ok = 7;
                """, diags);
        assertTrue(diags.hasErrors());
        assertEquals(1, decls.size());
        assertEquals("ok", decls.get(0).name());
    }

    @Test
    void automatonItemsParse() {
        final Decl.Automaton a = (Decl.Automaton) parseOk("""
                automaton strict : base {
                    use r1;
                    override<Chat> use r2;
                }
                """).get(0);
        assertEquals("base", a.parent());
        assertInstanceOf(Decl.Automaton.Use.class, a.items().get(0));
        final Decl.Automaton.Override o = (Decl.Automaton.Override) a.items().get(1);
        assertEquals("Chat", o.event());
    }

    /** Local diagnostic-code extraction, avoiding a harness dependency. */
    private static final class TestCodes {
        static List<String> of(final Diagnostics diags) {
            return diags.all().stream().map(d -> d.code()).toList();
        }
    }
}