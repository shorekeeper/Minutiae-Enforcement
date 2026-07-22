package org.synergyst.minutiae.lang.ast;

import java.util.List;

/**
 * A syntactic type reference.
 *
 * <p>A reference is a named head with optional type arguments, or an array of
 * an element reference. Resolution against the type environment is performed
 * by elaboration; at this level a reference carries surface structure only.
 */
public sealed interface TypeRef permits TypeRef.Named, TypeRef.ArrayOf {

    /** Returns the reference's source position. */
    Pos pos();

    /**
     * A named type reference, optionally applied to type arguments.
     *
     * @param name head identifier
     * @param args type arguments, possibly empty, never null
     * @param pos  source position
     */
    record Named(String name, List<TypeRef> args, Pos pos) implements TypeRef {
    }

    /**
     * An array type reference.
     *
     * @param element element reference
     * @param pos     source position of the opening bracket
     */
    record ArrayOf(TypeRef element, Pos pos) implements TypeRef {
    }
}