package org.synergyst.minutiae.lang.parse;

import org.synergyst.minutiae.lang.ast.*;
import org.synergyst.minutiae.lang.diag.DiagnosticSink;
import org.synergyst.minutiae.lang.lex.TokenBuffer;
import org.synergyst.minutiae.lang.lex.TokenKind;
import org.synergyst.minutiae.time.DurationSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.synergyst.minutiae.lang.lex.TokenKind.*;

/**
 * Recursive-descent parser of the definition language.
 *
 * <p>The parser consumes a terminated token buffer and yields the declaration
 * list of one compilation unit. Well-formed declarations are retained
 * regardless of sibling failures: on a syntax violation the enclosing
 * declaration is abandoned, a positioned diagnostic is reported, and the
 * cursor is resynchronised to the next declaration keyword.
 *
 * <p>Two constructions require lookahead beyond one token and are resolved by
 * bounded speculation: instantiation argument lists after a name or path, and
 * lambda parameter lists after an opening parenthesis. A speculation frame
 * saves the cursor, suppresses diagnostics, and restores the cursor on
 * failure; the non-speculative reparse of the same span produces any genuine
 * diagnostics. Control flow inside the parser uses a preallocated stackless
 * sentinel; no throwable escapes {@link #parse}.
 *
 * <p>Where a type-argument or specialisation-argument list is immediately
 * followed by an equals sign, the lexer's maximal munch yields a fused
 * {@code >=} token. The parser splits that token, treating it as a closing
 * angle followed by a pending assignment, so no whitespace is required at the
 * boundary.
 *
 * <p>The instance holds mutable cursor state and is single-use.
 */
public final class Parser {

    /** Stackless control-flow sentinel; carries no message and no trace. */
    private static final class Abort extends RuntimeException {
        static final Abort INSTANCE = new Abort();

        private Abort() {
            super(null, null, false, false);
        }
    }

    private final TokenBuffer buf;
    private final DiagnosticSink diags;
    private int cur;
    private int speculationDepth;
    private boolean pendingAssignFromGe;

    private Parser(final TokenBuffer buffer, final DiagnosticSink diagnostics) {
        this.buf = buffer;
        this.diags = diagnostics;
    }

    /**
     * Parses one compilation unit.
     *
     * @param buffer      the terminated token buffer
     * @param diagnostics the sink receiving syntax diagnostics
     * @return successfully parsed declarations, in source order
     */
    public static List<Decl> parse(final TokenBuffer buffer, final DiagnosticSink diagnostics) {
        return new Parser(buffer, diagnostics).unit();
    }

    // ------------------------------------------------------------------
    // Compilation unit
    // ------------------------------------------------------------------

    private List<Decl> unit() {
        final List<Decl> out = new ArrayList<>();
        while (!at(EOF)) {
            try {
                out.add(declaration());
            } catch (final Abort a) {
                syncTopLevel();
            }
        }
        return out;
    }

    private Decl declaration() {
        return switch (kind()) {
            case KW_CONST -> constDecl();
            case KW_SCHEMA -> schemaDecl();
            case KW_MATRIX -> matrixDecl();
            case KW_TRANSFORM -> transformDecl();
            case KW_EXPAND -> expandDecl();
            case KW_TEMPLATE -> templateDecl();
            case KW_RULE -> ruleDecl();
            case KW_AUTOMATON -> automatonDecl();
            default -> {
                report("P002", "expected a declaration but found " + describe());
                throw Abort.INSTANCE;
            }
        };
    }

    // ------------------------------------------------------------------
    // Declarations
    // ------------------------------------------------------------------

    private Decl constDecl() {
        final Pos pos = pos();
        expect(KW_CONST, "expected 'const'");
        final TypeRef type = type();
        final String name = ident("constant name");
        expectAssign();
        final Expr init = expr();
        expect(SEMI, "expected ';' after constant initializer");
        return new Decl.Const(type, name, init, pos);
    }

    private Decl schemaDecl() {
        final Pos pos = pos();
        expect(KW_SCHEMA, "expected 'schema'");
        final String name = ident("schema name");
        expect(LBRACE, "expected '{' after schema name");
        final List<Decl.Schema.Field> fields = new ArrayList<>(4);
        while (!at(RBRACE) && !at(EOF)) {
            final Pos fp = pos();
            final TypeRef ft = type();
            final String fn = nameLike("field name");
            expect(SEMI, "expected ';' after schema field");
            fields.add(new Decl.Schema.Field(ft, fn, fp));
        }
        expect(RBRACE, "expected '}' to close schema '" + name + "'");
        return new Decl.Schema(name, fields, pos);
    }

    private Decl matrixDecl() {
        final Pos pos = pos();
        expect(KW_MATRIX, "expected 'matrix'");
        expect(LT, "expected '<' after 'matrix'");
        final TypeRef schema = type();
        expectCloseAngle();
        final String name = ident("matrix name");
        expect(LBRACE, "expected '{' after matrix name");
        final List<Decl.Matrix.Row> rows = new ArrayList<>(8);
        while (!at(RBRACE) && !at(EOF)) {
            final Pos rp = pos();
            expect(KW_ROW, "expected 'row'");
            expect(LPAREN, "expected '(' after 'row'");
            final List<Expr> cells = new ArrayList<>(4);
            if (!at(RPAREN)) {
                cells.add(expr());
                while (eat(COMMA)) {
                    cells.add(expr());
                }
            }
            expect(RPAREN, "expected ')' to close row");
            expect(SEMI, "expected ';' after row");
            rows.add(new Decl.Matrix.Row(cells, rp));
        }
        expect(RBRACE, "expected '}' to close matrix '" + name + "'");
        return new Decl.Matrix(schema, name, rows, pos);
    }

    private Decl transformDecl() {
        final Pos pos = pos();
        expect(KW_TRANSFORM, "expected 'transform'");
        expect(LT, "expected '<' after 'transform'");
        final TypeRef from = type();
        expect(ARROW, "expected '->' between transform source and target types");
        final TypeRef to = type();
        expectCloseAngle();
        final String name = ident("transform name");
        expectAssign();
        final Expr body = expr();
        expect(SEMI, "expected ';' after transform body");
        return new Decl.Transform(from, to, name, body, pos);
    }

    private Decl expandDecl() {
        final Pos pos = pos();
        expect(KW_EXPAND, "expected 'expand'");
        final Expr application = expr();
        if (!(application instanceof Expr.Call)) {
            report("P007", "'expand' requires a transform application of the form name(argument)");
        }
        expect(SEMI, "expected ';' after expansion");
        return new Decl.Expand(application, pos);
    }

    private Decl templateDecl() {
        final Pos pos = pos();
        expect(KW_TEMPLATE, "expected 'template'");
        expect(LT, "expected '<' after 'template'");

        if (eat(GT)) {
            return templateSpec(pos);
        }
        final List<Decl.Template.Param> params = new ArrayList<>(2);
        do {
            final Pos pp = pos();
            final TypeRef pt = type();
            if (!at(IDENT)) {
                report("P011", "expected a template parameter name");
                throw Abort.INSTANCE;
            }
            params.add(new Decl.Template.Param(pt, ident("parameter name"), pp));
        } while (eat(COMMA));
        expectCloseAngle();

        final List<Expr> constraints = new ArrayList<>(2);
        if (eat(KW_WHERE)) {
            expect(LBRACE, "expected '{' after 'where'");
            if (at(RBRACE)) {
                report("P009", "constraint block must contain at least one constraint");
            }
            while (!at(RBRACE) && !at(EOF)) {
                constraints.add(expr());
                expect(SEMI, "expected ';' after constraint");
            }
            expect(RBRACE, "expected '}' to close constraint block");
        }
        final Decl.TemplateKind tk = templateKind();
        final String name = ident("template name");
        expectAssign();
        final Expr body = expr();
        expect(SEMI, "expected ';' after template body");
        return new Decl.Template(params, constraints, tk, name, body, pos);
    }

    private Decl templateSpec(final Pos pos) {
        final Decl.TemplateKind tk = templateKind();
        final String name = ident("specialised template name");
        expect(LT, "expected '<' to open specialisation arguments");
        final List<Decl.TemplateSpec.SpecArg> args = new ArrayList<>(2);
        do {
            if (at(WILDCARD)) {
                args.add(new Decl.TemplateSpec.Any(pos()));
                advance();
            } else {
                // Specialisation arguments share the instantiation-argument
                // grammar: additive only, so the closing '>' survives.
                args.add(new Decl.TemplateSpec.Given(addExpr()));
            }
        } while (eat(COMMA));
        expectCloseAngle();
        expectAssign();
        final Expr body = expr();
        expect(SEMI, "expected ';' after specialisation body");
        return new Decl.TemplateSpec(tk, name, args, body, pos);
    }

    private Decl.TemplateKind templateKind() {
        if (eat(KW_VERDICT)) {
            return Decl.TemplateKind.VERDICT;
        }
        if (eat(KW_GUARD)) {
            return Decl.TemplateKind.GUARD;
        }
        report("P001", "expected 'verdict' or 'guard' but found " + describe());
        throw Abort.INSTANCE;
    }

    private Decl ruleDecl() {
        final Pos pos = pos();
        expect(KW_RULE, "expected 'rule'");
        expect(LT, "expected '<' after 'rule'");
        final List<String> events = new ArrayList<>(2);
        events.add(ident("event type name"));
        while (eat(COMMA)) {
            events.add(ident("event type name"));
        }
        expectCloseAngle();
        final String name = ident("rule name");
        expect(LBRACE, "expected '{' after rule name");

        expect(KW_CONDITION, "expected 'condition'");
        expect(LBRACE, "expected '{' after 'condition'");
        expect(KW_TRIGGER, "expected 'trigger'");
        expectAssign();
        final Expr trigger = expr();
        expect(SEMI, "expected ';' after trigger expression");
        expect(KW_GUARD, "expected 'guard'");
        expectAssign();
        final Expr guard = expr();
        expect(SEMI, "expected ';' after guard expression");
        expect(RBRACE, "expected '}' to close condition block");

        expect(FATARROW, "expected '=>' between condition and verdict");
        expect(KW_VERDICT, "expected 'verdict' after '=>'");
        expectAssign();
        final Expr verdict = expr();
        expect(SEMI, "expected ';' after verdict expression");
        expect(RBRACE, "expected '}' to close rule '" + name + "'");
        return new Decl.Rule(events, name, trigger, guard, verdict, pos);
    }

    private Decl automatonDecl() {
        final Pos pos = pos();
        expect(KW_AUTOMATON, "expected 'automaton'");
        final String name = ident("automaton name");
        String parent = null;
        if (eat(COLON)) {
            parent = ident("parent automaton name");
        }
        expect(LBRACE, "expected '{' after automaton header");
        final List<Decl.Automaton.Item> items = new ArrayList<>(4);
        while (!at(RBRACE) && !at(EOF)) {
            final Pos ip = pos();
            if (eat(KW_USE)) {
                items.add(new Decl.Automaton.Use(ident("rule name"), ip));
            } else if (eat(KW_OVERRIDE)) {
                expect(LT, "expected '<' after 'override'");
                final String event = ident("event type name");
                expectCloseAngle();
                expect(KW_USE, "expected 'use' after override event");
                items.add(new Decl.Automaton.Override(event, ident("rule name"), ip));
            } else {
                report("P001", "expected 'use' or 'override' but found " + describe());
                throw Abort.INSTANCE;
            }
            expect(SEMI, "expected ';' after automaton item");
        }
        expect(RBRACE, "expected '}' to close automaton '" + name + "'");
        return new Decl.Automaton(name, parent, items, pos);
    }

    // ------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------

    private TypeRef type() {
        final Pos pos = pos();
        final String head = ident("type name");
        final List<TypeRef> args = new ArrayList<>(0);
        if (eat(LT)) {
            args.add(type());
            while (eat(COMMA)) {
                args.add(type());
            }
            expectCloseAngle();
        }
        TypeRef t = new TypeRef.Named(head, args, pos);
        while (at(LBRACKET)) {
            final Pos bp = pos();
            advance();
            expect(RBRACKET, "expected ']' in array type");
            t = new TypeRef.ArrayOf(t, bp);
        }
        return t;
    }

    // ------------------------------------------------------------------
    // Expressions (precedence-climbing)
    // ------------------------------------------------------------------

    private Expr expr() {
        return orExpr();
    }

    private Expr orExpr() {
        Expr left = andExpr();
        while (at(PIPEPIPE)) {
            final Pos p = pos();
            advance();
            left = new Expr.Binary(BinOp.OR, left, andExpr(), p);
        }
        return left;
    }

    private Expr andExpr() {
        Expr left = eqExpr();
        while (at(AMPAMP)) {
            final Pos p = pos();
            advance();
            left = new Expr.Binary(BinOp.AND, left, eqExpr(), p);
        }
        return left;
    }

    private Expr eqExpr() {
        Expr left = relExpr();
        while (at(EQEQ) || at(BANGEQ)) {
            final BinOp op = at(EQEQ) ? BinOp.EQ : BinOp.NE;
            final Pos p = pos();
            advance();
            left = new Expr.Binary(op, left, relExpr(), p);
        }
        return left;
    }

    private Expr relExpr() {
        Expr left = addExpr();
        while (at(LT) || at(GT) || at(LE) || at(GE) || at(TILDE)) {
            final BinOp op = switch (kind()) {
                case LT -> BinOp.LT;
                case GT -> BinOp.GT;
                case LE -> BinOp.LE;
                case GE -> BinOp.GE;
                default -> BinOp.MATCHES;
            };
            final Pos p = pos();
            advance();
            left = new Expr.Binary(op, left, addExpr(), p);
        }
        return left;
    }

    private Expr addExpr() {
        Expr left = mulExpr();
        while (at(PLUS) || at(MINUS)) {
            final BinOp op = at(PLUS) ? BinOp.ADD : BinOp.SUB;
            final Pos p = pos();
            advance();
            left = new Expr.Binary(op, left, mulExpr(), p);
        }
        return left;
    }

    private Expr mulExpr() {
        Expr left = unary();
        while (at(STAR) || at(SLASH)) {
            final BinOp op = at(STAR) ? BinOp.MUL : BinOp.DIV;
            final Pos p = pos();
            advance();
            left = new Expr.Binary(op, left, unary(), p);
        }
        return left;
    }

    private Expr unary() {
        if (at(BANG)) {
            final Pos p = pos();
            advance();
            return new Expr.Unary(UnOp.NOT, unary(), p);
        }
        if (at(MINUS)) {
            final Pos p = pos();
            advance();
            return new Expr.Unary(UnOp.NEG, unary(), p);
        }
        return postfix();
    }

    private Expr postfix() {
        Expr e = primary();
        while (true) {
            switch (kind()) {
                case LPAREN -> {
                    final Pos p = pos();
                    advance();
                    final List<Expr> args = new ArrayList<>(4);
                    if (!at(RPAREN)) {
                        args.add(expr());
                        while (eat(COMMA)) {
                            args.add(expr());
                        }
                    }
                    expect(RPAREN, "expected ')' to close argument list");
                    e = new Expr.Call(e, args, p);
                }
                case DOT -> {
                    final Pos p = pos();
                    advance();
                    e = new Expr.Member(e, nameLike("member name"), p);
                }
                case COLONCOLON -> {
                    final Pos p = pos();
                    advance();
                    final String seg = ident("path segment");
                    if (e instanceof Expr.NameRef n) {
                        e = new Expr.PathRef(List.of(n.name(), seg), n.pos());
                    } else if (e instanceof Expr.PathRef pr) {
                        final List<String> segs = new ArrayList<>(pr.segments());
                        segs.add(seg);
                        e = new Expr.PathRef(List.copyOf(segs), pr.pos());
                    } else {
                        report("P006", "'::' requires a name path on its left", p);
                        throw Abort.INSTANCE;
                    }
                }
                case LBRACE -> {
                    if (!isConstructible(e)) {
                        return e;
                    }
                    final Pos p = pos();
                    advance();
                    e = new Expr.RecordLit(e, recordBody(), p);
                }
                case KW_WITH -> {
                    final Pos p = pos();
                    advance();
                    expect(LBRACE, "expected '{' after 'with'");
                    e = new Expr.With(e, recordBody(), p);
                }
                case LT -> {
                    if (!(e instanceof Expr.NameRef) && !(e instanceof Expr.PathRef)) {
                        return e;
                    }
                    final List<Expr> args = tryInstantiationArgs();
                    if (args == null) {
                        return e;
                    }
                    e = new Expr.Instantiate(e, args, e.pos());
                }
                default -> {
                    return e;
                }
            }
        }
    }

    private static boolean isConstructible(final Expr e) {
        return e instanceof Expr.NameRef || e instanceof Expr.PathRef
                || e instanceof Expr.Instantiate;
    }

    /**
     * Speculatively parses an instantiation argument list at the current
     * {@code <}. Returns the arguments on success; restores the cursor and
     * returns null on any failure, in which case the {@code <} is left for
     * the relational operator.
     *
     * <p>Arguments are parsed with the additive grammar rather than the full
     * expression grammar: an instantiation argument is a value (a measure
     * path, a duration, an integer, a text, a rule identifier, or their
     * arithmetic combination), never a comparison, and admitting relational
     * operators here would let a trailing argument consume the closing
     * {@code >} as a greater-than operator. A comparison over a template
     * parameter belongs in the template's {@code where} block, which lies
     * outside the brackets and retains the full grammar. A parenthesised
     * argument still reaches the full grammar through the primary rule.
     */
    private List<Expr> tryInstantiationArgs() {
        final int save = cur;
        speculationDepth++;
        try {
            advance(); // '<'
            final List<Expr> args = new ArrayList<>(2);
            args.add(addExpr());
            while (eat(COMMA)) {
                args.add(addExpr());
            }
            if (!at(GT)) {
                cur = save;
                return null;
            }
            advance();
            if (!followsInstantiation(kind())) {
                cur = save;
                return null;
            }
            return args;
        } catch (final Abort a) {
            cur = save;
            return null;
        } finally {
            speculationDepth--;
        }
    }

    private static boolean followsInstantiation(final TokenKind k) {
        return switch (k) {
            case LPAREN, LBRACE, RPAREN, RBRACE, RBRACKET,
                 COMMA, SEMI, DOT, FATARROW, KW_WITH -> true;
            default -> false;
        };
    }

    private Expr primary() {
        final Pos p = pos();
        switch (kind()) {
            case INT -> {
                final long v = buf.intValue(cur);
                advance();
                return new Expr.IntLit(v, p);
            }
            case REAL -> {
                final double v = buf.realValue(cur);
                advance();
                return new Expr.RealLit(v, p);
            }
            case DURATION -> {
                final String lexeme = buf.lexeme(cur);
                advance();
                try {
                    return new Expr.DurLit(DurationSpec.parse(lexeme), p);
                } catch (final IllegalArgumentException ex) {
                    report("P013", "invalid duration literal: " + ex.getMessage(), p);
                    throw Abort.INSTANCE;
                }
            }
            case TEXT -> {
                final String v = buf.textValue(cur);
                advance();
                return new Expr.TextLit(v, p);
            }
            case RULE_ID -> {
                final String id = buf.lexeme(cur);
                advance();
                return new Expr.RuleLit(id, p);
            }
            case KW_TRUE -> {
                advance();
                return new Expr.BoolLit(true, p);
            }
            case KW_FALSE -> {
                advance();
                return new Expr.BoolLit(false, p);
            }
            case IDENT -> {
                final String name = buf.lexeme(cur);
                advance();
                return new Expr.NameRef(name, p);
            }
            case LPAREN -> {
                return lambdaOrGroup(p);
            }
            case LBRACE -> {
                return listOrAnonymousRecord(p);
            }
            case KW_MATCH -> {
                return matchExpr(p);
            }
            case WILDCARD -> {
                report("P004", "the wildcard is not an expression");
                throw Abort.INSTANCE;
            }
            default -> {
                report("P003", "expected an expression but found " + describe());
                throw Abort.INSTANCE;
            }
        }
    }

    /**
     * Speculatively parses a lambda at the current {@code (}. Falls back to a
     * parenthesised expression when the parameter-list form does not match.
     */
    private Expr lambdaOrGroup(final Pos p) {
        final int save = cur;
        speculationDepth++;
        try {
            advance(); // '('
            final List<String> params = new ArrayList<>(2);
            if (at(IDENT)) {
                params.add(buf.lexeme(cur));
                advance();
                while (eat(COMMA)) {
                    if (!at(IDENT)) {
                        cur = save;
                        params.clear();
                        break;
                    }
                    params.add(buf.lexeme(cur));
                    advance();
                }
            }
            if (cur != save && at(RPAREN)) {
                final int afterParen = cur;
                advance();
                if (at(ARROW)) {
                    advance();
                    speculationDepth--;
                    try {
                        final Expr body = expr();
                        return new Expr.Lambda(List.copyOf(params), body, p);
                    } finally {
                        speculationDepth++;
                    }
                }
                cur = afterParen;
                cur = save;
            } else {
                cur = save;
            }
        } catch (final Abort a) {
            cur = save;
        } finally {
            speculationDepth--;
        }
        // Parenthesised expression.
        expect(LPAREN, "expected '('");
        final Expr inner = expr();
        expect(RPAREN, "expected ')' to close grouped expression");
        return inner;
    }

    private Expr listOrAnonymousRecord(final Pos p) {
        advance(); // '{'
        // Anonymous record: the body opens with ".name =", where the name may
        // be a keyword, matching the record-body field grammar.
        if (at(DOT) && (kindAt(cur + 1) == IDENT || isKeyword(kindAt(cur + 1)))
                && kindAt(cur + 2) == ASSIGN) {
            return new Expr.RecordLit(null, recordBody(), p);
        }
        final List<Expr> items = new ArrayList<>(4);
        if (!at(RBRACE)) {
            items.add(expr());
            while (eat(COMMA)) {
                if (at(RBRACE)) {
                    break; // trailing comma
                }
                items.add(expr());
            }
        }
        expect(RBRACE, "expected '}' to close list literal");
        return new Expr.ListLit(items, p);
    }

    /** Parses record fields up to and including the closing brace. */
    private List<Expr.FieldInit> recordBody() {
        final List<Expr.FieldInit> fields = new ArrayList<>(6);
        final Set<String> seen = new HashSet<>(8);
        while (!at(RBRACE) && !at(EOF)) {
            final Pos fp = pos();
            expect(DOT, "expected '.' to begin a field initializer");
            final String fname = nameLike("field name");
            expectAssign();
            final Expr value = expr();
            if (!seen.add(fname)) {
                report("P010", "duplicate field initializer '." + fname + "'", fp);
            }
            fields.add(new Expr.FieldInit(fname, value, fp));
            if (!eat(COMMA)) {
                break;
            }
        }
        expect(RBRACE, "expected '}' to close record body");
        return fields;
    }

    private Expr matchExpr(final Pos p) {
        expect(KW_MATCH, "expected 'match'");
        expect(LPAREN, "expected '(' after 'match'");
        final Expr subject = expr();
        expect(RPAREN, "expected ')' after match subject");
        expect(LBRACE, "expected '{' to open match arms");
        final List<Expr.Arm> arms = new ArrayList<>(4);
        while (!at(RBRACE) && !at(EOF)) {
            final Pos ap = pos();
            final Pattern pat = pattern();
            expect(FATARROW, "expected '=>' in match arm");
            final Expr body = expr();
            arms.add(new Expr.Arm(pat, body, ap));
            if (!eat(COMMA)) {
                break;
            }
        }
        expect(RBRACE, "expected '}' to close match");
        return new Expr.Match(subject, arms, p);
    }

    private Pattern pattern() {
        final Pos p = pos();
        if (at(INT)) {
            final long lo = buf.intValue(cur);
            advance();
            if (eat(DOTDOT)) {
                if (!at(INT)) {
                    report("P008", "expected an integer after '..'");
                    throw Abort.INSTANCE;
                }
                final long hi = buf.intValue(cur);
                advance();
                return new Pattern.RangePat(lo, hi, p);
            }
            return new Pattern.IntPat(lo, p);
        }
        if (at(WILDCARD)) {
            advance();
            return new Pattern.WildPat(p);
        }
        if (at(IDENT)) {
            final List<String> segs = new ArrayList<>(2);
            segs.add(buf.lexeme(cur));
            advance();
            while (eat(COLONCOLON)) {
                segs.add(ident("path segment"));
            }
            return new Pattern.PathPat(List.copyOf(segs), p);
        }
        report("P008", "expected a pattern: integer, range, path, or '_'");
        throw Abort.INSTANCE;
    }

    // ------------------------------------------------------------------
    // Recovery and token machinery
    // ------------------------------------------------------------------

    private void syncTopLevel() {
        final int entered = cur;
        while (!at(EOF)) {
            switch (kind()) {
                case KW_CONST, KW_SCHEMA, KW_MATRIX, KW_TRANSFORM, KW_EXPAND,
                     KW_TEMPLATE, KW_RULE, KW_AUTOMATON -> {
                    if (cur != entered) {
                        return;
                    }
                    advance();
                }
                default -> advance();
            }
        }
    }

    private String ident(final String what) {
        if (at(IDENT)) {
            final String name = buf.lexeme(cur);
            advance();
            return name;
        }
        report("P001", "expected " + what + " but found " + describe());
        throw Abort.INSTANCE;
    }

    private void expect(final TokenKind k, final String message) {
        if (at(k)) {
            advance();
            return;
        }
        report("P001", message + " but found " + describe());
        throw Abort.INSTANCE;
    }

    /** Consumes '=', honouring a pending half of a previously split '>='. */
    private void expectAssign() {
        if (pendingAssignFromGe) {
            pendingAssignFromGe = false;
            return;
        }
        expect(ASSIGN, "expected '='");
    }

    /** Consumes a closing '>' of an angle-bracketed list, splitting '>='. */
    private void expectCloseAngle() {
        if (at(GT)) {
            advance();
            return;
        }
        if (at(GE)) {
            pendingAssignFromGe = true;
            advance();
            return;
        }
        report("P001", "expected '>' but found " + describe());
        throw Abort.INSTANCE;
    }

    private void report(final String code, final String message) {
        report(code, message, pos());
    }

    private void report(final String code, final String message, final Pos p) {
        if (speculationDepth == 0) {
            diags.error(code, p.line(), p.column(), message);
        }
    }

    private boolean eat(final TokenKind k) {
        if (at(k)) {
            advance();
            return true;
        }
        return false;
    }

    private boolean at(final TokenKind k) {
        return kind() == k;
    }

    private TokenKind kind() {
        return buf.kind(cur);
    }

    private TokenKind kindAt(final int i) {
        return i < buf.size() ? buf.kind(i) : EOF;
    }

    private void advance() {
        if (cur < buf.size() - 1) {
            cur++;
        }
    }

    private Pos pos() {
        return new Pos(buf.line(cur), buf.column(cur));
    }

    private String describe() {
        final TokenKind k = kind();
        return switch (k) {
            case EOF -> "end of input";
            case ERROR -> "invalid token";
            default -> "'" + buf.lexeme(cur) + "'";
        };
    }

    /**
     * Consumes a name in a position where only a name can appear, admitting
     * keywords as ordinary identifiers.
     *
     * <p>Record field names, member-access names, and schema field names are
     * syntactically unambiguous: the preceding token ({@code .} or a field
     * type) fixes the interpretation, so a word that elsewhere serves as a
     * keyword ({@code rule}, {@code guard}, {@code with}) is admitted here as
     * a plain name. Without this admission a descriptor field could never be
     * named after a keyword, which the built-in {@code Layout} ({@code .rule})
     * and {@code Step} ({@code .guard}) descriptors require.
     *
     * @param what diagnostic description of the expected name
     * @return the name lexeme
     */
    private String nameLike(final String what) {
        if (at(IDENT) || isKeyword(kind())) {
            final String name = buf.lexeme(cur);
            advance();
            return name;
        }
        report("P001", "expected " + what + " but found " + describe());
        throw Abort.INSTANCE;
    }

    /** Reports whether a token class is a reserved keyword. */
    private static boolean isKeyword(final TokenKind k) {
        return switch (k) {
            case KW_RULE, KW_CONDITION, KW_TRIGGER, KW_GUARD, KW_VERDICT,
                 KW_TEMPLATE, KW_WHERE, KW_AUTOMATON, KW_OVERRIDE, KW_USE,
                 KW_CONST, KW_SCHEMA, KW_MATRIX, KW_ROW, KW_TRANSFORM,
                 KW_EXPAND, KW_MATCH, KW_WITH, KW_TRUE, KW_FALSE -> true;
            default -> false;
        };
    }
}