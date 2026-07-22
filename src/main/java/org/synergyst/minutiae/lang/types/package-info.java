/**
 * Type and effect model of the definition language.
 *
 * <p>The model is nominal for declared aggregates and structural for function
 * and list shapes. Primitive types, built-in sum types, built-in record types,
 * and event types form a closed world defined by
 * {@link org.synergyst.minutiae.lang.types.Builtins}; user sources extend the
 * world only with record schemas, whose names live in the same namespace and
 * are checked for collision at elaboration.
 *
 * <p>The language admits no implicit conversion of any kind. Integer, real,
 * and duration arithmetic are disjoint; equality and ordering are defined only
 * within a single type; text matching is defined only between text operands.
 * Every operand-type violation is a positioned elaboration error.
 *
 * <p>Effects form a two-point lattice {@code PURE < QUERY}. An expression
 * position carries an effect allowance; an environment-reading built-in
 * function may be called only where {@code QUERY} is allowed. Compile-time
 * positions (constant initializers, matrix cells, trigger expressions,
 * template constraints, transform bodies) allow {@code PURE} only; guard
 * bodies allow {@code QUERY}; verdict bodies allow {@code PURE}.
 */
package org.synergyst.minutiae.lang.types;