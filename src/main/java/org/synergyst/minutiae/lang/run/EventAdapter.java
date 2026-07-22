package org.synergyst.minutiae.lang.run;

import org.synergyst.minutiae.engine.EventFacts;
import org.synergyst.minutiae.lang.eval.Value;
import org.synergyst.minutiae.lang.types.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapts platform event facts into the record values the interpreter
 * consumes.
 *
 * <p>Conversion follows the record shape field by field: {@code subject}
 * becomes the canonical UUID text of the subject account (the empty text when
 * the subject is unknown), {@code subject_name} the display name, and every
 * remaining field is drawn from the facts map by name and converted by the
 * field's declared type. A fact absent from the map or carrying an
 * incompatible runtime class yields the field type's neutral value (zero,
 * empty text, false), so a malformed platform event degrades to unsatisfied
 * guard comparisons rather than a dispatch fault - the fail-safe posture of
 * the engine.
 *
 * <p>The adapter is stateless and allocation-bounded: one record value and
 * one backing list per event, sized exactly to the shape.
 */
public final class EventAdapter {

    private EventAdapter() {
    }

    /**
     * Converts event facts into a record value of the given shape.
     *
     * @param facts the platform event facts
     * @param shape the record shape derived from the event catalogue
     * @return the record value presented to guard and verdict bodies
     */
    public static Value.RecordV toRecord(final EventFacts facts, final Type.Rec shape) {
        final List<Value> fields = new ArrayList<>(shape.fields().size());
        for (final Type.Field field : shape.fields()) {
            fields.add(switch (field.name()) {
                case "subject" -> new Value.TextV(
                        facts.subject() == null ? "" : facts.subject().toString());
                case "subject_name" -> new Value.TextV(
                        facts.subjectName() == null ? "" : facts.subjectName());
                default -> convert(facts.field(field.name()), field.type());
            });
        }
        return new Value.RecordV(shape, List.copyOf(fields));
    }

    private static Value convert(final Object raw, final Type declared) {
        if (declared == Type.INT) {
            return new Value.IntV(raw instanceof Number n ? n.longValue() : 0L);
        }
        if (declared == Type.REAL) {
            return new Value.RealV(raw instanceof Number n ? n.doubleValue() : 0.0d);
        }
        if (declared == Type.BOOL) {
            return new Value.BoolV(raw instanceof Boolean b && b);
        }
        // Text is the remaining event-field type; a foreign runtime class is
        // rendered rather than rejected, preserving fail-safe dispatch.
        return new Value.TextV(raw == null ? "" : String.valueOf(raw));
    }
}