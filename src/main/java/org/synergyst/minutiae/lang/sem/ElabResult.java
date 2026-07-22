package org.synergyst.minutiae.lang.sem;

import org.synergyst.minutiae.lang.ast.Decl;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Outcome of elaborating one compilation unit.
 *
 * @param declarations declarations that elaborated successfully, in order
 * @param symbols      the global symbol table, in declaration order
 * @param tables       the identity-keyed elaboration side tables
 */
public record ElabResult(List<Decl> declarations,
                         LinkedHashMap<String, Symbol> symbols,
                         ElabTables tables) {
}