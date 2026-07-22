/**
 * Compile-time evaluation of the definition language.
 *
 * <p>The evaluator normalises an elaborated compilation unit into first-order
 * residual values: constants, matrices, transforms, layouts stamped by
 * expansion, rules with their triggers reduced to data and their guards and
 * verdicts retained as closed function values, and automata resolved by set
 * algebra to ordered rule lists. Evaluation is total over elaborated input:
 * every residual dynamic condition (constraint satisfaction, arithmetic
 * bounds, trigger shape, key uniqueness) is checked here with a positioned
 * diagnostic, and a failing declaration is skipped without affecting
 * siblings.
 *
 * <p>Guard and verdict bodies are never executed at compile time; they are
 * captured as closures over the compile-time environment, so every template
 * parameter and constant they reference is already bound to a value inside
 * the closure environment.
 *
 * <h2>Diagnostic codes</h2>
 * <p>{@code V001} invalid trigger shape; {@code V002} invalid sequence steps;
 * {@code V003} template constraint not satisfied; {@code V005} division by
 * zero; {@code V006} integer overflow; {@code V008} invalid duration
 * arithmetic; {@code V012} evaluation depth exceeded; {@code V013} value is
 * not applicable; {@code V015} invalid text pattern; {@code V016} duplicate
 * layout key; {@code V017} duplicate automaton member; {@code W001} override
 * removed no inherited rule.
 */
package org.synergyst.minutiae.lang.eval;