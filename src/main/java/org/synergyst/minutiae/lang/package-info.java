// src/main/java/org/synergyst/minutiae/lang/package-info.java
/**
 * Definition language of the moderation automaton subsystem.
 *
 * <p>The language is a declarative, load-time-compiled notation in which every
 * construct denotes a typed value. A source file is compiled in full before any
 * rule is armed; the runtime executes only immutable plans produced by
 * compilation and performs no parsing, no name resolution, and no validation of
 * its own.
 *
 * <h2>Compilation phases</h2>
 * <ol>
 *   <li><b>Lexing</b> ({@link org.synergyst.minutiae.lang.lex}) - a single
 *       left-to-right pass producing a structure-of-arrays token buffer.</li>
 *   <li><b>Parsing</b> ({@link org.synergyst.minutiae.lang.parse}) - recursive
 *       descent over the token buffer producing the abstract syntax tree
 *       ({@link org.synergyst.minutiae.lang.ast}).</li>
 *   <li><b>Elaboration</b> - name resolution, type checking, effect checking,
 *       and exhaustiveness analysis.</li>
 *   <li><b>Normalisation</b> - evaluation of every compile-time construct
 *       (constants, templates, transforms, matrices, automaton set algebra)
 *       into first-order residual plans.</li>
 *   <li><b>Verification</b> - totality and referential-integrity checks over
 *       the residual plans.</li>
 *   <li><b>Lowering</b> - translation of the residual plans onto the runtime
 *       dispatch engine.</li>
 * </ol>
 *
 * <h2>Lexical grammar</h2>
 * <pre>
 * token        = keyword | ident | wildcard | int | real | duration | text
 *              | rule-id | punct | operator ;
 *
 * keyword      = "rule" | "condition" | "trigger" | "guard" | "verdict"
 *              | "template" | "where" | "automaton" | "override" | "use"
 *              | "const" | "schema" | "matrix" | "row" | "transform"
 *              | "expand" | "match" | "with" | "true" | "false" ;
 *
 * ident        = ident-start , { ident-part } ;         (* not a keyword *)
 * ident-start  = letter | "_" ;
 * ident-part   = letter | digit | "_" ;
 * wildcard     = "_" ;                                  (* exactly one char *)
 *
 * int          = digit , { digit } ;
 * real         = digit , { digit } , "." , digit , { digit } ;
 * duration     = digit , { digit } , unit , { digit , { digit } , unit } ;
 * unit         = "s" | "m" | "h" | "d" | "w" ;
 * text         = '"' , { char - '"' | escape } , '"' ;
 * escape       = "\" , ( '"' | "\" | "n" | "t" | "r" ) ;
 * rule-id      = "P" , ( "." , digit , { digit } )+ ;   (* no interior space *)
 *
 * punct        = "{" | "}" | "(" | ")" | "[" | "]" | "," | ";" ;
 * operator     = "::" | ":" | "->" | "=>" | "==" | "=" | "!=" | "!"
 *              | "&lt;=" | "&lt;" | "&gt;=" | "&gt;" | "&amp;&amp;" | "||"
 *              | ".." | "." | "+" | "-" | "*" | "/" | "~" ;
 *
 * comment      = "//" , { char } , newline
 *              | "/*" , { char } , "*&#47;" ;
 * </pre>
 *
 * <p>Classification is by maximal munch with three refinements. First, a word
 * consisting of exactly one underscore is the wildcard token. Second, an
 * identifier consisting of exactly the letter {@code P}, immediately followed
 * by a dot and a digit, begins a rule identifier and is consumed greedily as
 * one {@code rule-id} token; the dot is otherwise never part of a word. Third,
 * a digit run immediately followed by a recognised unit letter begins a
 * duration literal, whose grammar requires strict alternation of digit runs
 * and unit letters; a violation is a lexical error, not two tokens.
 *
 * <h2>Syntactic grammar</h2>
 * <pre>
 * unit         = { decl } EOF ;
 * decl         = const-decl | schema-decl | matrix-decl | transform-decl
 *              | expand-decl | template-decl | rule-decl | automaton-decl ;
 *
 * const-decl   = "const" type IDENT "=" expr ";" ;
 * schema-decl  = "schema" IDENT "{" { type IDENT ";" } "}" ;
 * matrix-decl  = "matrix" "&lt;" type "&gt;" IDENT
 *                "{" { "row" "(" [ expr { "," expr } ] ")" ";" } "}" ;
 * transform-decl = "transform" "&lt;" type "-&gt;" type "&gt;" IDENT "=" expr ";" ;
 * expand-decl  = "expand" expr ";" ;        (* expr must be an application *)
 *
 * template-decl = "template" "&lt;" tparams "&gt;" [ where-block ]
 *                 tkind IDENT "=" expr ";"
 *               | "template" "&lt;" "&gt;" tkind IDENT
 *                 "&lt;" sarg { "," sarg } "&gt;" "=" expr ";" ;
 * tparams      = type IDENT { "," type IDENT } ;
 * sarg         = "_" | expr ;
 * where-block  = "where" "{" expr ";" { expr ";" } "}" ;
 * tkind        = "verdict" | "guard" ;
 *
 * rule-decl    = "rule" "&lt;" IDENT { "," IDENT } "&gt;" IDENT "{"
 *                  "condition" "{"
 *                     "trigger" "=" expr ";"
 *                     "guard"   "=" expr ";"
 *                  "}"
 *                  "=&gt;" "verdict" "=" expr ";"
 *                "}" ;
 *
 * automaton-decl = "automaton" IDENT [ ":" IDENT ] "{" { auto-item } "}" ;
 * auto-item    = "use" IDENT ";"
 *              | "override" "&lt;" IDENT "&gt;" "use" IDENT ";" ;
 *
 * type         = IDENT [ "&lt;" type { "," type } "&gt;" ] , { "[" "]" } ;
 *
 * expr         = or-expr ;
 * or-expr      = and-expr  { "||" and-expr } ;
 * and-expr     = eq-expr   { "&amp;&amp;" eq-expr } ;
 * eq-expr      = rel-expr  { ( "==" | "!=" ) rel-expr } ;
 * rel-expr     = add-expr  { ( "&lt;" | "&gt;" | "&lt;=" | "&gt;=" | "~" ) add-expr } ;
 * add-expr     = mul-expr  { ( "+" | "-" ) mul-expr } ;
 * mul-expr     = unary     { ( "*" | "/" ) unary } ;
 * unary        = "!" unary | "-" unary | postfix ;
 * postfix      = primary , { call | member | path-seg | construct
 *                          | inst-args | update } ;
 * call         = "(" [ expr { "," expr } ] ")" ;
 * member       = "." IDENT ;
 * path-seg     = "::" IDENT ;
 * construct    = "{" record-body "}" ;      (* receiver: name, path, inst *)
 * inst-args    = "&lt;" add-expr { "," add-expr } "&gt;" ;
 *                (* additive grammar: relational operators are excluded so
 *                   that the closing angle is never consumed as an operator;
 *                   a parenthesised argument regains the full grammar *)
 * update       = "with" "{" record-body "}" ;
 * record-body  = [ field { "," field } [ "," ] ] ;
 * field        = "." field-name "=" expr ;
 * field-name   = IDENT | keyword ;
 *                (* the position after '.' admits keywords as names *)
 * primary      = INT | REAL | DURATION | TEXT | RULE-ID | "true" | "false"
 *              | IDENT | lambda | "(" expr ")" | list | match-expr ;
 * lambda       = "(" [ IDENT { "," IDENT } ] ")" "-&gt;" expr ;
 * list         = "{" [ expr { "," expr } [ "," ] ] "}" ;
 *              | "{" field { "," field } [ "," ] "}" ;  (* anonymous record *)
 * match-expr   = "match" "(" expr ")" "{" arm { "," arm } [ "," ] "}" ;
 * arm          = pattern "=&gt;" expr ;
 * pattern      = INT [ ".." INT ] | "_" | IDENT { "::" IDENT } ;
 * </pre>
 *
 * <h2>Disambiguation</h2>
 * <ul>
 *   <li><b>Instantiation versus comparison.</b> A {@code &lt;} following a
 *       name or path expression is tried as an instantiation argument list by
 *       speculative parse; the attempt succeeds only when a well-formed
 *       argument list closed by {@code &gt;} is immediately followed by one of
 *       {@code ( ) , ; } { . =&gt; with ]}. On any failure the cursor is
 *       restored and {@code &lt;} is parsed as the relational operator.
 *       Speculation suppresses diagnostics; a genuinely malformed input is
 *       re-diagnosed on the non-speculative path.</li>
 *   <li><b>Lambda versus grouping.</b> A {@code (} is tried as a parameter
 *       list: zero or more identifiers, {@code )}, {@code -&gt;}. On failure
 *       the cursor is restored and a parenthesised expression is parsed.</li>
 *   <li><b>List versus anonymous record.</b> A bare {@code {} in expression
 *       position opens an anonymous record literal when the next three tokens
 *       are {@code . IDENT =}, and a list literal otherwise.</li>
 *   <li><b>Member versus composition.</b> {@code a.b} is a single syntactic
 *       form; whether it denotes field access or transform composition is
 *       decided by elaboration from the type of the receiver.</li>
 *   <li><b>Fused {@code &gt;=}.</b> Where a type-argument or
 *       specialisation-argument list is immediately followed by {@code =},
 *       maximal munch produces {@code &gt;=}. The parser splits that token
 *       into a closing {@code &gt;} and a pending {@code =}, so no whitespace
 *       is required before the equals sign.</li>
 *   <li><b>Keyword field names.</b> After {@code .} in a record body, a
 *       member access, and after a field type in a schema, only a name can
 *       occur; keywords are therefore admitted as ordinary names in exactly
 *       these positions and nowhere else.</li>
 * </ul>
 *
 * <h2>Diagnostics</h2>
 * <p>All failures are reported as positioned diagnostics; no phase throws
 * past its entry point. Codes are stable identifiers:
 * {@code L001} unexpected character; {@code L002} unterminated text literal;
 * {@code L003} invalid escape; {@code L004} malformed duration literal;
 * {@code L005} integer literal overflow; {@code L006} unterminated block
 * comment; {@code L007} stray {@code &amp;}; {@code L008} stray {@code |};
 * {@code P001} expected token; {@code P002} expected declaration;
 * {@code P003} expected expression; {@code P004} wildcard not permitted;
 * {@code P005} construction requires a type path; {@code P006} path segment
 * requires a name path; {@code P007} expand requires an application;
 * {@code P008} expected pattern; {@code P009} empty constraint block;
 * {@code P010} duplicate field initializer; {@code P011} expected template
 * parameter; {@code P013} invalid duration value.
 *
 * <h2>Threading and performance</h2>
 * <p>Every phase is confined to the loading thread. The token buffer is a
 * structure of parallel primitive arrays; identifiers and keywords are
 * classified without allocation, and lexemes are materialised lazily on
 * demand. Parser backtracking is bounded by construction (one speculative
 * frame per {@code &lt;} or {@code (}) and uses a stackless control-flow
 * sentinel, never a stack-trace-bearing exception.
 */
package org.synergyst.minutiae.lang;