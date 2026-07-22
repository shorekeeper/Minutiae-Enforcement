package org.synergyst.minutiae.lang.types;

import java.util.List;

/**
 * A type of the definition language.
 *
 * <p>The representation is a closed sealed hierarchy. Named forms (sums,
 * records, events) compare by name, which is sound because every name is
 * registered exactly once, either by {@link Builtins} or by schema
 * elaboration under collision checking. Structural forms (functions, lists,
 * steps, rule shapes) compare component-wise.
 *
 * <p>Assignability is identity for all forms except three deliberate axes:
 * an event type is assignable to the distinguished base event; a step type is
 * assignable to the base step; function types are assignable contravariantly
 * in parameters, covariantly in the result, and monotonically in effect.
 * List types are invariant.
 */
public sealed interface Type
        permits Type.Prim, Type.Sum, Type.Rec, Type.Event, Type.ListT,
        Type.Func, Type.Step, Type.RuleT {

    /** Primitive kinds. The set is closed. */
    enum PrimKind {
        BOOL, INT, REAL, TEXT, DURATION, RULE, ACCOUNT
    }

    /** A primitive type. */
    record Prim(PrimKind kind) implements Type {
    }

    /** One named, typed component of a record, event, or constructor. */
    record Field(String name, Type type) {
    }

    /** One constructor of a sum type; nullary when {@code fields} is empty. */
    record Ctor(String name, List<Field> fields) {

        /** Resolves a field index by name, or -1 when absent. */
        public int fieldIndex(final String fieldName) {
            for (int i = 0; i < fields.size(); i++) {
                if (fields.get(i).name().equals(fieldName)) {
                    return i;
                }
            }
            return -1;
        }
    }

    /**
     * A nominal sum type. {@code name} doubles as the value-namespace path
     * root under which constructors are addressed.
     */
    record Sum(String name, List<Ctor> ctors) implements Type {

        /** Resolves a constructor by name, or null when absent. */
        public Ctor ctor(final String ctorName) {
            for (final Ctor c : ctors) {
                if (c.name().equals(ctorName)) {
                    return c;
                }
            }
            return null;
        }
    }

    /** A nominal record type with an ordered, mandatory field list. */
    record Rec(String name, List<Field> fields) implements Type {

        /** Resolves a field index by name, or -1 when absent. */
        public int fieldIndex(final String fieldName) {
            for (int i = 0; i < fields.size(); i++) {
                if (fields.get(i).name().equals(fieldName)) {
                    return i;
                }
            }
            return -1;
        }
    }

    /** A nominal event type with an ordered field list. */
    record Event(String name, List<Field> fields) implements Type {

        /** Resolves a field type by name, or null when absent. */
        public Type field(final String fieldName) {
            for (final Field f : fields) {
                if (f.name().equals(fieldName)) {
                    return f.type();
                }
            }
            return null;
        }
    }

    /** A homogeneous, invariant list type. */
    record ListT(Type elem) implements Type {
    }

    /** A function type with an explicit effect grant for its body. */
    record Func(List<Type> params, Type result, Effect effect) implements Type {
    }

    /** The type of one sequence step bound to an event type. */
    record Step(Event event) implements Type {
    }

    /** The shape of a rule over an ordered event list. */
    record RuleT(List<Event> events) implements Type {
    }

    // ------------------------------------------------------------------
    // Canonical instances of primitives
    // ------------------------------------------------------------------

    Type BOOL = new Prim(PrimKind.BOOL);
    Type INT = new Prim(PrimKind.INT);
    Type REAL = new Prim(PrimKind.REAL);
    Type TEXT = new Prim(PrimKind.TEXT);
    Type DURATION = new Prim(PrimKind.DURATION);
    Type RULE = new Prim(PrimKind.RULE);
    Type ACCOUNT = new Prim(PrimKind.ACCOUNT);

    // ------------------------------------------------------------------
    // Relations
    // ------------------------------------------------------------------

    /**
     * Structural-or-nominal type identity.
     *
     * @param a first type
     * @param b second type
     * @return {@code true} when the types are the same type
     */
    static boolean same(final Type a, final Type b) {
        if (a == b) {
            return true;
        }
        return switch (a) {
            case Prim pa -> b instanceof Prim pb && pa.kind() == pb.kind();
            case Sum sa -> b instanceof Sum sb && sa.name().equals(sb.name());
            case Rec ra -> b instanceof Rec rb && ra.name().equals(rb.name());
            case Event ea -> b instanceof Event eb && ea.name().equals(eb.name());
            case ListT la -> b instanceof ListT lb && same(la.elem(), lb.elem());
            case Step sa -> b instanceof Step sb && same(sa.event(), sb.event());
            case RuleT ra -> b instanceof RuleT rb && eventsSame(ra.events(), rb.events());
            case Func fa -> b instanceof Func fb
                    && fa.effect() == fb.effect()
                    && same(fa.result(), fb.result())
                    && paramsSame(fa.params(), fb.params());
        };
    }

    private static boolean eventsSame(final List<Event> a, final List<Event> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).name().equals(b.get(i).name())) {
                return false;
            }
        }
        return true;
    }

    private static boolean paramsSame(final List<Type> a, final List<Type> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!same(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Assignability of a value of type {@code from} to a position of type
     * {@code to}.
     *
     * @param from the value's type
     * @param to   the position's type
     * @return {@code true} when the assignment is admissible
     */
    static boolean assignable(final Type from, final Type to) {
        if (same(from, to)) {
            return true;
        }
        if (from instanceof Event && to instanceof Event te) {
            return te.name().equals(Builtins.ANY_EVENT_NAME);
        }
        if (from instanceof Step && to instanceof Step ts) {
            return ts.event().name().equals(Builtins.ANY_EVENT_NAME);
        }
        if (from instanceof Func ff && to instanceof Func tf) {
            if (ff.params().size() != tf.params().size()) {
                return false;
            }
            for (int i = 0; i < ff.params().size(); i++) {
                if (!assignable(tf.params().get(i), ff.params().get(i))) {
                    return false;
                }
            }
            return assignable(ff.result(), tf.result()) && ff.effect().le(tf.effect());
        }
        return false;
    }

    /**
     * Renders a type for diagnostic display.
     *
     * @param t the type
     * @return the display form
     */
    static String show(final Type t) {
        return switch (t) {
            case Prim p -> switch (p.kind()) {
                case BOOL -> "Bool";
                case INT -> "Int";
                case REAL -> "Real";
                case TEXT -> "Text";
                case DURATION -> "Duration";
                case RULE -> "Rule";
                case ACCOUNT -> "Account";
            };
            case Sum s -> s.name();
            case Rec r -> r.name();
            case Event e -> e.name();
            case ListT l -> show(l.elem()) + "[]";
            case Step s -> "Step<" + s.event().name() + ">";
            case RuleT r -> {
                final StringBuilder sb = new StringBuilder("Rule<");
                for (int i = 0; i < r.events().size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(r.events().get(i).name());
                }
                yield sb.append('>').toString();
            }
            case Func f -> {
                final StringBuilder sb = new StringBuilder("(");
                for (int i = 0; i < f.params().size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(show(f.params().get(i)));
                }
                yield sb.append(") -> ").append(show(f.result())).toString();
            }
        };
    }
}