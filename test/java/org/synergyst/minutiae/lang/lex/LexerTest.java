package org.synergyst.minutiae.lang.lex;

import org.junit.jupiter.api.Test;
import org.synergyst.minutiae.lang.diag.Diagnostics;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.synergyst.minutiae.lang.lex.TokenKind.*;

/**
 * Lexical classification, literal decoding, and error recovery of the lexer.
 */
final class LexerTest {

    private static List<TokenKind> kinds(final String src) {
        final TokenBuffer buf = Lexer.lex(src, new Diagnostics());
        final List<TokenKind> out = new ArrayList<>();
        for (int i = 0; i < buf.size() - 1; i++) {
            out.add(buf.kind(i));
        }
        return out;
    }

    @Test
    void classifiesKeywordsAndIdentifiers() {
        assertEquals(List.of(KW_RULE, KW_CONDITION, KW_TRIGGER, KW_GUARD, KW_VERDICT, IDENT),
                kinds("rule condition trigger guard verdict rulex"));
    }

    @Test
    void wildcardIsExactlyOneUnderscore() {
        assertEquals(List.of(WILDCARD, IDENT, IDENT), kinds("_ _x x_"));
    }

    @Test
    void ruleIdentifierIsGreedy() {
        final TokenBuffer buf = Lexer.lex("P.3.2 P.1", new Diagnostics());
        assertEquals(RULE_ID, buf.kind(0));
        assertEquals("P.3.2", buf.lexeme(0));
        assertEquals(RULE_ID, buf.kind(1));
        assertEquals("P.1", buf.lexeme(1));
    }

    @Test
    void bareLetterPWithoutDigitIsIdentifier() {
        assertEquals(List.of(IDENT, DOT, IDENT), kinds("P.x"));
    }

    @Test
    void decodesIntRealAndDuration() {
        final TokenBuffer buf = Lexer.lex("42 3.5 1d12h", new Diagnostics());
        assertEquals(INT, buf.kind(0));
        assertEquals(42L, buf.intValue(0));
        assertEquals(REAL, buf.kind(1));
        assertEquals(3.5d, buf.realValue(1));
        assertEquals(DURATION, buf.kind(2));
        assertEquals("1d12h", buf.lexeme(2));
    }

    @Test
    void integerRangeIsThreeTokens() {
        assertEquals(List.of(INT, DOTDOT, INT), kinds("1..2"));
    }

    @Test
    void malformedDurationIsReported() {
        final Diagnostics diags = new Diagnostics();
        final TokenBuffer buf = Lexer.lex("3hx", diags);
        assertTrue(diags.hasErrors());
        assertEquals("L004", diags.all().get(0).code());
        assertEquals(ERROR, buf.kind(0));
    }

    @Test
    void integerOverflowIsReported() {
        final Diagnostics diags = new Diagnostics();
        Lexer.lex("99999999999999999999", diags);
        assertEquals("L005", diags.all().get(0).code());
    }

    @Test
    void textLiteralDecodesEscapes() {
        final TokenBuffer buf = Lexer.lex("\"a\\\"b\\n\"", new Diagnostics());
        assertEquals(TEXT, buf.kind(0));
        assertEquals("a\"b\n", buf.textValue(0));
    }

    @Test
    void unterminatedTextIsReported() {
        final Diagnostics diags = new Diagnostics();
        Lexer.lex("\"abc", diags);
        assertEquals("L002", diags.all().get(0).code());
    }

    @Test
    void strayAmpersandIsReportedAndScanContinues() {
        final Diagnostics diags = new Diagnostics();
        final TokenBuffer buf = Lexer.lex("a & b", diags);
        assertEquals("L007", diags.all().get(0).code());
        assertEquals(IDENT, buf.kind(2));
    }

    @Test
    void fusedGreaterEqualsIsOneToken() {
        assertEquals(List.of(WILDCARD, GE), kinds("_>="));
        assertEquals(List.of(GT, ASSIGN), kinds("> ="));
    }

    @Test
    void commentsAreTrivia() {
        assertEquals(List.of(IDENT, IDENT),
                kinds("a // line\n/* block\nstill */ b"));
    }
}