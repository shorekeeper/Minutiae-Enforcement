package org.synergyst.minutiae.lang.sem;

import org.synergyst.minutiae.lang.ast.Expr;
import org.synergyst.minutiae.lang.types.Type;

import java.util.IdentityHashMap;
import java.util.List;

/**
 * Identity-keyed side tables produced by elaboration and consumed by
 * evaluation.
 *
 * <p>Keys are abstract-syntax node identities; the tables therefore attach
 * facts to occurrences, not to structurally equal expressions. The tables are
 * written by the single elaboration pass and are read-only thereafter.
 */
public final class ElabTables {

    /** Elaborated interpretation of a member access. */
    public enum MemberKind {

        /** Access of a named field on a record or event value. */
        FIELD,

        /** Right-to-left composition of two single-parameter pure functions. */
        COMPOSE
    }

    /** Elaborated constructed shape of a record literal. */
    public sealed interface Constructed permits RecC, VariantC, StepC, RuleC {
    }

    /** Construction of a nominal record. */
    public record RecC(Type.Rec type) implements Constructed {
    }

    /** Construction of a sum variant through named fields. */
    public record VariantC(Type.Sum sum, Type.Ctor ctor) implements Constructed {
    }

    /** Construction of a sequence step bound to an event type. */
    public record StepC(Type.Step type) implements Constructed {
    }

    /** Construction of a rule value. */
    public record RuleC(Type.RuleT type) implements Constructed {
    }

    /** Elaborated type of each expression occurrence. */
    public final IdentityHashMap<Expr, Type> typeOf = new IdentityHashMap<>(256);

    /** Resolved symbol of each name, path, and instantiation-target occurrence. */
    public final IdentityHashMap<Expr, Symbol> refOf = new IdentityHashMap<>(128);

    /** Interpretation of each member-access occurrence. */
    public final IdentityHashMap<Expr, MemberKind> memberKind = new IdentityHashMap<>(32);

    /** Constructed shape of each record-literal occurrence. */
    public final IdentityHashMap<Expr, Constructed> ctorOf = new IdentityHashMap<>(64);

    /** Resolved parameter types of each lambda occurrence. */
    public final IdentityHashMap<Expr, List<Type>> lambdaParams = new IdentityHashMap<>(32);
}