/**
 * Semantic analysis of the definition language.
 *
 * <p>The elaborator performs, in one source-order pass over the parsed
 * declarations: name binding under a declaration-before-use discipline, full
 * bidirectional type checking with no implicit coercions, effect checking
 * against per-position allowances, exhaustiveness and reachability analysis
 * of match expressions, and structural validation of every declaration form.
 *
 * <p>Results are recorded in identity-keyed side tables rather than a second
 * tree, so downstream evaluation walks the original abstract syntax with
 * elaborated facts attached. A declaration that fails elaboration is reported
 * and skipped; well-formed siblings elaborate normally. No throwable escapes
 * the entry point.
 *
 * <h2>Diagnostic codes</h2>
 * <p>{@code E001} unknown name; {@code E002} unknown type; {@code E003} type
 * mismatch; {@code E004} unknown field; {@code E005} missing field;
 * {@code E006} not constructible; {@code E007} effect violation; {@code E008}
 * arity mismatch; {@code E009} lambda requires expected function type;
 * {@code E010} invalid match subject; {@code E011} non-exhaustive match;
 * {@code E012} unreachable arm; {@code E013} pattern incompatible with
 * subject; {@code E014} duplicate definition; {@code E015} unknown member;
 * {@code E016} invalid composition; {@code E017} invalid template use;
 * {@code E018} specialisation arity mismatch; {@code E019} operator operand
 * type; {@code E022} invalid automaton item; {@code E023} invalid expansion;
 * {@code E025} unknown event; {@code E028} inadmissible template parameter
 * type; {@code E032} type does not support equality; {@code E033} untyped
 * empty list; {@code E035} constructor requires arguments; {@code E036} name
 * is not a value; {@code E037} expression is not callable.
 */
package org.synergyst.minutiae.lang.sem;