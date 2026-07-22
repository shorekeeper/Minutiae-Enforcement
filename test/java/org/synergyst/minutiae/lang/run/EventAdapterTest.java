package org.synergyst.minutiae.lang.run;

import org.junit.jupiter.api.Test;
import org.synergyst.minutiae.engine.EventFacts;
import org.synergyst.minutiae.engine.EventKind;
import org.synergyst.minutiae.lang.eval.Value;
import org.synergyst.minutiae.lang.types.Builtins;
import org.synergyst.minutiae.lang.types.Type;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conversion of platform event facts into interpreter record values,
 * including neutral degradation of absent or foreign-typed facts.
 */
final class EventAdapterTest {

    private static Type.Rec chatShape() {
        final Type.Event ev = Builtins.event("Chat");
        return new Type.Rec(ev.name(), ev.fields());
    }

    @Test
    void mapsCommonAndDeclaredFields() {
        final UUID subject = UUID.randomUUID();
        final Value.RecordV rec = EventAdapter.toRecord(
                new EventFacts(EventKind.CHAT, subject, "Steve",
                        Map.of("message", "hi", "length", 2L)),
                chatShape());
        assertEquals(subject.toString(), ((Value.TextV) rec.field("subject")).value());
        assertEquals("Steve", ((Value.TextV) rec.field("subject_name")).value());
        assertEquals("hi", ((Value.TextV) rec.field("message")).value());
        assertEquals(2L, ((Value.IntV) rec.field("length")).value());
    }

    @Test
    void absentFactsDegradeToNeutralValues() {
        final Value.RecordV rec = EventAdapter.toRecord(
                new EventFacts(EventKind.CHAT, null, null, Map.of()),
                chatShape());
        assertEquals("", ((Value.TextV) rec.field("subject")).value());
        assertEquals("", ((Value.TextV) rec.field("subject_name")).value());
        assertEquals("", ((Value.TextV) rec.field("message")).value());
        assertEquals(0L, ((Value.IntV) rec.field("length")).value());
    }

    @Test
    void foreignRuntimeClassDegradesToNeutralValue() {
        final Value.RecordV rec = EventAdapter.toRecord(
                new EventFacts(EventKind.CHAT, null, "x",
                        Map.of("length", "not-a-number")),
                chatShape());
        assertEquals(0L, ((Value.IntV) rec.field("length")).value());
    }
}