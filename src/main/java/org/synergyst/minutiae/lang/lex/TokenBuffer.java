package org.synergyst.minutiae.lang.lex;

import java.util.ArrayList;

/**
 * Structure-of-arrays token storage.
 *
 * <p>Each token occupies one slot across seven parallel arrays: class ordinal,
 * start offset, end offset, line, column, a 64-bit numeric payload, and an
 * auxiliary index into a string pool. This layout keeps the parser's scan
 * cache-resident and avoids one object allocation per token.
 *
 * <p>Payload discipline by class: {@link TokenKind#INT} carries its decoded
 * value in the numeric payload; {@link TokenKind#REAL} carries the raw IEEE-754
 * bits of its decoded value; {@link TokenKind#TEXT} carries a pool index of the
 * escape-decoded string. All other classes carry no payload; their surface form
 * is recovered lazily from the retained source via {@link #lexeme(int)}.
 *
 * <p>The buffer is written by exactly one thread during lexing and is
 * immutable thereafter; subsequent concurrent reads require no
 * synchronisation because publication occurs through the final reference
 * returned by the lexer entry point.
 */
public final class TokenBuffer {

    private final String source;

    private int[] kind;
    private int[] start;
    private int[] end;
    private int[] line;
    private int[] col;
    private long[] num;
    private int[] aux;

    private final ArrayList<String> pool = new ArrayList<>(8);
    private int count;

    TokenBuffer(final String source, final int initialCapacity) {
        this.source = source;
        final int cap = Math.max(16, initialCapacity);
        this.kind = new int[cap];
        this.start = new int[cap];
        this.end = new int[cap];
        this.line = new int[cap];
        this.col = new int[cap];
        this.num = new long[cap];
        this.aux = new int[cap];
    }

    // ------------------------------------------------------------------
    // Write surface (lexer-private).
    // ------------------------------------------------------------------

    void add(final TokenKind k, final int startOffset, final int endOffset,
             final int tokenLine, final int tokenColumn) {
        addFull(k, startOffset, endOffset, tokenLine, tokenColumn, 0L, -1);
    }

    void addNum(final TokenKind k, final int startOffset, final int endOffset,
                final int tokenLine, final int tokenColumn, final long value) {
        addFull(k, startOffset, endOffset, tokenLine, tokenColumn, value, -1);
    }

    void addText(final TokenKind k, final int startOffset, final int endOffset,
                 final int tokenLine, final int tokenColumn, final String decoded) {
        pool.add(decoded);
        addFull(k, startOffset, endOffset, tokenLine, tokenColumn, 0L, pool.size() - 1);
    }

    private void addFull(final TokenKind k, final int s, final int e,
                         final int ln, final int cl, final long n, final int a) {
        if (count == kind.length) {
            grow();
        }
        final int i = count++;
        kind[i] = k.ordinal();
        start[i] = s;
        end[i] = e;
        line[i] = ln;
        col[i] = cl;
        num[i] = n;
        aux[i] = a;
    }

    private void grow() {
        final int cap = kind.length << 1;
        kind = java.util.Arrays.copyOf(kind, cap);
        start = java.util.Arrays.copyOf(start, cap);
        end = java.util.Arrays.copyOf(end, cap);
        line = java.util.Arrays.copyOf(line, cap);
        col = java.util.Arrays.copyOf(col, cap);
        num = java.util.Arrays.copyOf(num, cap);
        aux = java.util.Arrays.copyOf(aux, cap);
    }

    // ------------------------------------------------------------------
    // Read surface.
    // ------------------------------------------------------------------

    /** Returns the number of tokens, including the terminal EOF token. */
    public int size() {
        return count;
    }

    /** Returns the class of the token at an index. */
    public TokenKind kind(final int i) {
        return TokenKind.of(kind[i]);
    }

    /** Returns the one-based line of the token at an index. */
    public int line(final int i) {
        return line[i];
    }

    /** Returns the one-based column of the token at an index. */
    public int column(final int i) {
        return col[i];
    }

    /**
     * Materialises the verbatim source slice of the token at an index. The
     * slice is computed on demand; no lexeme is retained by the buffer itself.
     */
    public String lexeme(final int i) {
        return source.substring(start[i], end[i]);
    }

    /** Returns the decoded value of an {@link TokenKind#INT} token. */
    public long intValue(final int i) {
        return num[i];
    }

    /** Returns the decoded value of a {@link TokenKind#REAL} token. */
    public double realValue(final int i) {
        return Double.longBitsToDouble(num[i]);
    }

    /** Returns the escape-decoded body of a {@link TokenKind#TEXT} token. */
    public String textValue(final int i) {
        return pool.get(aux[i]);
    }
}