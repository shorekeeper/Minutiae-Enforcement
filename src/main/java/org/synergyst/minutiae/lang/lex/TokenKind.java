package org.synergyst.minutiae.lang.lex;

/**
 * Token classes of the definition language.
 *
 * <p>The set is closed: every token the lexer emits carries exactly one class.
 * Ordinal values are used as the compact on-buffer representation and must not
 * be reordered once released; new classes are appended.
 */
public enum TokenKind {

    // Names and literals.
    IDENT, WILDCARD, INT, REAL, DURATION, TEXT, RULE_ID,

    // Keywords.
    KW_RULE, KW_CONDITION, KW_TRIGGER, KW_GUARD, KW_VERDICT,
    KW_TEMPLATE, KW_WHERE, KW_AUTOMATON, KW_OVERRIDE, KW_USE,
    KW_CONST, KW_SCHEMA, KW_MATRIX, KW_ROW, KW_TRANSFORM,
    KW_EXPAND, KW_MATCH, KW_WITH, KW_TRUE, KW_FALSE,

    // Punctuation.
    LBRACE, RBRACE, LPAREN, RPAREN, LBRACKET, RBRACKET, COMMA, SEMI,

    // Operators.
    COLONCOLON, COLON, ARROW, FATARROW, ASSIGN,
    EQEQ, BANGEQ, LT, GT, LE, GE,
    AMPAMP, PIPEPIPE, BANG,
    DOTDOT, DOT, PLUS, MINUS, STAR, SLASH, TILDE,

    // Control.
    ERROR, EOF;

    private static final TokenKind[] BY_ORDINAL = values();

    /** Resolves a class from its ordinal without a defensive copy. */
    static TokenKind of(final int ordinal) {
        return BY_ORDINAL[ordinal];
    }
}