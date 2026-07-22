package org.synergyst.minutiae.lang.eval;

import org.synergyst.minutiae.lang.ast.Expr;
import org.synergyst.minutiae.lang.types.Type;
import org.synergyst.minutiae.time.DurationSpec;

import java.util.List;

/**
 * A compile-time value of the definition language.
 *
 * <p>Values are immutable. Aggregate values reference their types so that
 * field access and rendering require no side lookup. Function values are
 * closures over an {@link Env} chain, composed function values pair an outer
 * and an inner applicable, and the distinguished always-guard is its own
 * variant. Rule values pair reduced trigger data with unreduced guard and
 * verdict function values.
 */
public sealed interface Value permits Value.BoolV, Value.IntV, Value.RealV,
        Value.TextV, Value.DurV, Value.RuleIdV, Value.VariantV, Value.RecordV,
        Value.ListV, Value.StepV, Value.ClosureV, Value.ComposeV,
        Value.BuiltinV, Value.AlwaysV, Value.RuleV {

    /** A boolean value. */
    record BoolV(boolean value) implements Value {
    }

    /** A signed 64-bit integer value. */
    record IntV(long value) implements Value {
    }

    /** An IEEE-754 double value. */
    record RealV(double value) implements Value {
    }

    /** A text value. */
    record TextV(String value) implements Value {
    }

    /** A finite duration value. */
    record DurV(DurationSpec value) implements Value {
    }

    /** A rule-identifier value. */
    record RuleIdV(String id) implements Value {
    }

    /** A sum-variant value; {@code fields} follows constructor field order. */
    record VariantV(Type.Sum sum, String ctor, List<Value> fields) implements Value {

        /** Returns the field at a constructor field index. */
        public Value field(final int index) {
            return fields.get(index);
        }
    }

    /** A record value; {@code fields} follows record field order. */
    record RecordV(Type.Rec type, List<Value> fields) implements Value {

        /** Returns the field value by name, or null when absent. */
        public Value field(final String name) {
            final int idx = type.fieldIndex(name);
            return idx < 0 ? null : fields.get(idx);
        }
    }

    /** A list value. */
    record ListV(List<Value> items) implements Value {
    }

    /** A sequence-step value binding an event type to a guard value. */
    record StepV(Type.Event event, Value guard) implements Value {
    }

    /** A closure over parameters, a body, and a captured environment. */
    record ClosureV(List<String> params, Expr body, Env env, Type.Func type) implements Value {
    }

    /** A right-to-left composition of two applicable values. */
    record ComposeV(Value outer, Value inner) implements Value {
    }

    /** A reference to an environment-reading built-in function. */
    record BuiltinV(String name, Type.Func type) implements Value {
    }

    /** The always-true guard. */
    record AlwaysV() implements Value {
        /** The single instance. */
        public static final AlwaysV INSTANCE = new AlwaysV();
    }

    /**
     * A residual rule: reduced trigger data with guard and verdict function
     * values.
     *
     * @param name    rule name; synthesised for expanded rules
     * @param events  ordered event types the rule observes
     * @param trigger reduced trigger variant
     * @param guard   guard value: a closure or the always-guard
     * @param verdict verdict value: a closure
     */
    record RuleV(String name, List<Type.Event> events, VariantV trigger,
                 Value guard, Value verdict) implements Value {

        /** Returns a copy of this rule under another name. */
        public RuleV named(final String newName) {
            return new RuleV(newName, events, trigger, guard, verdict);
        }
    }

    /**
     * Structural value equality, defined over every value form that can occur
     * in a compile-time comparison. Function values compare by identity.
     *
     * @param a first value
     * @param b second value
     * @return {@code true} when the values are equal
     */
    static boolean equal(final Value a, final Value b) {
        if (a == b) {
            return true;
        }
        return switch (a) {
            case BoolV x -> b instanceof BoolV y && x.value() == y.value();
            case IntV x -> b instanceof IntV y && x.value() == y.value();
            case RealV x -> b instanceof RealV y
                    && Double.compare(x.value(), y.value()) == 0;
            case TextV x -> b instanceof TextV y && x.value().equals(y.value());
            case RuleIdV x -> b instanceof RuleIdV y && x.id().equals(y.id());
            case DurV x -> b instanceof DurV y
                    && x.value().permanent() == y.value().permanent()
                    && x.value().millis() == y.value().millis();
            case VariantV x -> b instanceof VariantV y
                    && x.sum().name().equals(y.sum().name())
                    && x.ctor().equals(y.ctor())
                    && listsEqual(x.fields(), y.fields());
            case RecordV x -> b instanceof RecordV y
                    && x.type().name().equals(y.type().name())
                    && listsEqual(x.fields(), y.fields());
            case ListV x -> b instanceof ListV y && listsEqual(x.items(), y.items());
            case StepV x -> b instanceof StepV y
                    && x.event().name().equals(y.event().name())
                    && equal(x.guard(), y.guard());
            default -> false;
        };
    }

    private static boolean listsEqual(final List<Value> a, final List<Value> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!equal(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Renders a canonical key for a value, used for template-instantiation
     * memoisation. Two values render equal keys exactly when {@link #equal}
     * holds; function values never occur as instantiation arguments.
     *
     * @param v the value
     * @return the canonical key
     */
    static String key(final Value v) {
        final StringBuilder sb = new StringBuilder(24);
        keyInto(v, sb);
        return sb.toString();
    }

    private static void keyInto(final Value v, final StringBuilder sb) {
        switch (v) {
            case BoolV x -> sb.append(x.value() ? "#t" : "#f");
            case IntV x -> sb.append('i').append(x.value());
            case RealV x -> sb.append('r').append(Double.doubleToLongBits(x.value()));
            case TextV x -> sb.append('t').append(x.value().length()).append(':').append(x.value());
            case RuleIdV x -> sb.append('p').append(x.id());
            case DurV x -> sb.append('d').append(x.value().millis());
            case VariantV x -> {
                sb.append('v').append(x.sum().name()).append(':').append(x.ctor()).append('(');
                for (final Value f : x.fields()) {
                    keyInto(f, sb);
                    sb.append(',');
                }
                sb.append(')');
            }
            case RecordV x -> {
                sb.append('c').append(x.type().name()).append('(');
                for (final Value f : x.fields()) {
                    keyInto(f, sb);
                    sb.append(',');
                }
                sb.append(')');
            }
            case ListV x -> {
                sb.append('[');
                for (final Value f : x.items()) {
                    keyInto(f, sb);
                    sb.append(',');
                }
                sb.append(']');
            }
            case StepV x -> sb.append('s').append(x.event().name());
            default -> sb.append('@').append(System.identityHashCode(v));
        }
    }
}