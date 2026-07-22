package org.synergyst.minutiae.lang.lex;

import org.synergyst.minutiae.lang.diag.DiagnosticSink;

/**
 * Single-pass lexer of the definition language.
 *
 * <p>The scan proceeds left to right with at most two characters of lookahead
 * and no backtracking. Whitespace and comments are discarded between tokens.
 * Classification follows maximal munch; the refinements for wildcard, rule
 * identifiers, and duration literals are specified in the package
 * documentation.
 *
 * <p>Lexical violations do not abort the scan: the offending span is reported
 * through the diagnostic sink, an {@link TokenKind#ERROR} token is emitted so
 * downstream positions remain addressable, and scanning resumes at the next
 * character. The token stream therefore always terminates with
 * {@link TokenKind#EOF} regardless of input.
 *
 * <p>Keyword recognition compares source characters in place and allocates
 * nothing. Integer values are accumulated during the scan with overflow
 * detection; real values are decoded once at scan time and stored as raw
 * bits. The instance holds mutable cursor state and is single-use.
 */
public final class Lexer {

    private final String src;
    private final int len;
    private final TokenBuffer out;
    private final DiagnosticSink diags;

    private int pos;
    private int line = 1;
    private int col = 1;

    private Lexer(final String source, final DiagnosticSink diagnostics) {
        this.src = source;
        this.len = source.length();
        this.out = new TokenBuffer(source, Math.max(64, source.length() / 6));
        this.diags = diagnostics;
    }

    /**
     * Tokenises an entire source.
     *
     * @param source      the source text; must not be null
     * @param diagnostics the sink receiving lexical diagnostics
     * @return the terminated token buffer
     */
    public static TokenBuffer lex(final String source, final DiagnosticSink diagnostics) {
        final Lexer lexer = new Lexer(source, diagnostics);
        lexer.run();
        return lexer.out;
    }

    private void run() {
        while (true) {
            skipTrivia();
            if (pos >= len) {
                out.add(TokenKind.EOF, pos, pos, line, col);
                return;
            }
            scanToken();
        }
    }

    // ------------------------------------------------------------------
    // Token producers
    // ------------------------------------------------------------------

    private void scanToken() {
        final int startPos = pos;
        final int startLine = line;
        final int startCol = col;
        final char c = src.charAt(pos);

        if (isIdentStart(c)) {
            word(startPos, startLine, startCol);
            return;
        }
        if (isDigit(c)) {
            number(startPos, startLine, startCol);
            return;
        }
        if (c == '"') {
            text(startPos, startLine, startCol);
            return;
        }
        operator(startPos, startLine, startCol);
    }

    private void word(final int startPos, final int startLine, final int startCol) {
        advance();
        while (pos < len && isIdentPart(src.charAt(pos))) {
            advance();
        }
        final int wordLen = pos - startPos;

        if (wordLen == 1 && src.charAt(startPos) == '_') {
            out.add(TokenKind.WILDCARD, startPos, pos, startLine, startCol);
            return;
        }
        // Rule identifier: exactly "P" immediately followed by ".digit".
        if (wordLen == 1 && src.charAt(startPos) == 'P'
                && pos + 1 < len && src.charAt(pos) == '.' && isDigit(src.charAt(pos + 1))) {
            while (pos + 1 < len && src.charAt(pos) == '.' && isDigit(src.charAt(pos + 1))) {
                advance(); // '.'
                while (pos < len && isDigit(src.charAt(pos))) {
                    advance();
                }
            }
            out.add(TokenKind.RULE_ID, startPos, pos, startLine, startCol);
            return;
        }
        final TokenKind kw = keyword(startPos, wordLen);
        out.add(kw != null ? kw : TokenKind.IDENT, startPos, pos, startLine, startCol);
    }

    private void number(final int startPos, final int startLine, final int startCol) {
        long value = 0L;
        boolean overflow = false;
        while (pos < len && isDigit(src.charAt(pos))) {
            final int d = src.charAt(pos) - '0';
            if (value > (Long.MAX_VALUE - d) / 10L) {
                overflow = true;
            } else {
                value = value * 10L + d;
            }
            advance();
        }
        // Real literal: '.' immediately followed by a digit. A '.' followed by
        // anything else (including a second '.') is left to the operator scan.
        if (pos + 1 < len && src.charAt(pos) == '.' && isDigit(src.charAt(pos + 1))) {
            advance();
            while (pos < len && isDigit(src.charAt(pos))) {
                advance();
            }
            final double real = Double.parseDouble(src.substring(startPos, pos));
            out.addNum(TokenKind.REAL, startPos, pos, startLine, startCol,
                    Double.doubleToRawLongBits(real));
            return;
        }
        // Duration literal: strict alternation (digits unit)+.
        if (pos < len && isUnit(src.charAt(pos))) {
            boolean malformed = false;
            while (true) {
                if (pos >= len || !isUnit(src.charAt(pos))) {
                    malformed = true;
                    break;
                }
                advance(); // unit letter
                if (pos >= len || !isDigit(src.charAt(pos))) {
                    break;
                }
                while (pos < len && isDigit(src.charAt(pos))) {
                    advance();
                }
            }
            // A letter run after a complete literal (e.g. "3hx") is malformed.
            if (!malformed && pos < len && Character.isLetter(src.charAt(pos))) {
                malformed = true;
                while (pos < len && Character.isLetterOrDigit(src.charAt(pos))) {
                    advance();
                }
            }
            if (malformed) {
                diags.error("L004", startLine, startCol,
                        "malformed duration literal '" + src.substring(startPos, pos) + "'");
                out.add(TokenKind.ERROR, startPos, pos, startLine, startCol);
                return;
            }
            out.add(TokenKind.DURATION, startPos, pos, startLine, startCol);
            return;
        }
        if (overflow) {
            diags.error("L005", startLine, startCol,
                    "integer literal exceeds the signed 64-bit range");
            out.add(TokenKind.ERROR, startPos, pos, startLine, startCol);
            return;
        }
        out.addNum(TokenKind.INT, startPos, pos, startLine, startCol, value);
    }

    private void text(final int startPos, final int startLine, final int startCol) {
        advance(); // opening quote
        final StringBuilder sb = new StringBuilder(16);
        while (pos < len && src.charAt(pos) != '"') {
            char c = src.charAt(pos);
            advance();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= len) {
                break;
            }
            final char esc = src.charAt(pos);
            advance();
            switch (esc) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                default -> diags.error("L003", line, col - 2,
                        "invalid escape '\\" + esc + "'");
            }
        }
        if (pos >= len) {
            diags.error("L002", startLine, startCol, "unterminated text literal");
            out.add(TokenKind.ERROR, startPos, pos, startLine, startCol);
            return;
        }
        advance(); // closing quote
        out.addText(TokenKind.TEXT, startPos, pos, startLine, startCol, sb.toString());
    }

    private void operator(final int startPos, final int startLine, final int startCol) {
        final char c = src.charAt(pos);
        advance();
        final TokenKind k = switch (c) {
            case '{' -> TokenKind.LBRACE;
            case '}' -> TokenKind.RBRACE;
            case '(' -> TokenKind.LPAREN;
            case ')' -> TokenKind.RPAREN;
            case '[' -> TokenKind.LBRACKET;
            case ']' -> TokenKind.RBRACKET;
            case ',' -> TokenKind.COMMA;
            case ';' -> TokenKind.SEMI;
            case '~' -> TokenKind.TILDE;
            case '+' -> TokenKind.PLUS;
            case '*' -> TokenKind.STAR;
            case '/' -> TokenKind.SLASH;
            case '-' -> match('>') ? TokenKind.ARROW : TokenKind.MINUS;
            case '=' -> match('>') ? TokenKind.FATARROW
                    : match('=') ? TokenKind.EQEQ : TokenKind.ASSIGN;
            case ':' -> match(':') ? TokenKind.COLONCOLON : TokenKind.COLON;
            case '.' -> match('.') ? TokenKind.DOTDOT : TokenKind.DOT;
            case '!' -> match('=') ? TokenKind.BANGEQ : TokenKind.BANG;
            case '<' -> match('=') ? TokenKind.LE : TokenKind.LT;
            case '>' -> match('=') ? TokenKind.GE : TokenKind.GT;
            case '&' -> match('&') ? TokenKind.AMPAMP : null;
            case '|' -> match('|') ? TokenKind.PIPEPIPE : null;
            default -> null;
        };
        if (k != null) {
            out.add(k, startPos, pos, startLine, startCol);
            return;
        }
        final String code = c == '&' ? "L007" : c == '|' ? "L008" : "L001";
        final String message = switch (c) {
            case '&' -> "stray '&'; the conjunction operator is '&&'";
            case '|' -> "stray '|'; the disjunction operator is '||'";
            default -> "unexpected character '" + c + "'";
        };
        diags.error(code, startLine, startCol, message);
        out.add(TokenKind.ERROR, startPos, pos, startLine, startCol);
    }

    // ------------------------------------------------------------------
    // Keyword recognition (allocation-free, length-dispatched)
    // ------------------------------------------------------------------

    private TokenKind keyword(final int start, final int wordLen) {
        return switch (wordLen) {
            case 3 -> is(start, "row") ? TokenKind.KW_ROW
                    : is(start, "use") ? TokenKind.KW_USE : null;
            case 4 -> is(start, "rule") ? TokenKind.KW_RULE
                    : is(start, "with") ? TokenKind.KW_WITH
                    : is(start, "true") ? TokenKind.KW_TRUE : null;
            case 5 -> is(start, "guard") ? TokenKind.KW_GUARD
                    : is(start, "where") ? TokenKind.KW_WHERE
                    : is(start, "const") ? TokenKind.KW_CONST
                    : is(start, "match") ? TokenKind.KW_MATCH
                    : is(start, "false") ? TokenKind.KW_FALSE : null;
            case 6 -> is(start, "schema") ? TokenKind.KW_SCHEMA
                    : is(start, "matrix") ? TokenKind.KW_MATRIX
                    : is(start, "expand") ? TokenKind.KW_EXPAND : null;
            case 7 -> is(start, "trigger") ? TokenKind.KW_TRIGGER
                    : is(start, "verdict") ? TokenKind.KW_VERDICT : null;
            case 8 -> is(start, "template") ? TokenKind.KW_TEMPLATE
                    : is(start, "override") ? TokenKind.KW_OVERRIDE : null;
            case 9 -> is(start, "condition") ? TokenKind.KW_CONDITION
                    : is(start, "automaton") ? TokenKind.KW_AUTOMATON
                    : is(start, "transform") ? TokenKind.KW_TRANSFORM : null;
            default -> null;
        };
    }

    private boolean is(final int start, final String kw) {
        for (int i = 0, n = kw.length(); i < n; i++) {
            if (src.charAt(start + i) != kw.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Trivia and character machinery
    // ------------------------------------------------------------------

    private void skipTrivia() {
        while (pos < len) {
            final char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                advance();
            } else if (c == '/' && pos + 1 < len && src.charAt(pos + 1) == '/') {
                while (pos < len && src.charAt(pos) != '\n') {
                    advance();
                }
            } else if (c == '/' && pos + 1 < len && src.charAt(pos + 1) == '*') {
                final int cl = line;
                final int cc = col;
                advance();
                advance();
                boolean closed = false;
                while (pos < len) {
                    if (src.charAt(pos) == '*' && pos + 1 < len && src.charAt(pos + 1) == '/') {
                        advance();
                        advance();
                        closed = true;
                        break;
                    }
                    advance();
                }
                if (!closed) {
                    diags.error("L006", cl, cc, "unterminated block comment");
                }
            } else {
                return;
            }
        }
    }

    private boolean match(final char expected) {
        if (pos < len && src.charAt(pos) == expected) {
            advance();
            return true;
        }
        return false;
    }

    private void advance() {
        if (src.charAt(pos) == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
        pos++;
    }

    private static boolean isDigit(final char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isUnit(final char c) {
        return c == 's' || c == 'm' || c == 'h' || c == 'd' || c == 'w';
    }

    private static boolean isIdentStart(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentPart(final char c) {
        return isIdentStart(c) || isDigit(c);
    }
}