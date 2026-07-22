package org.synergyst.minutiae.engine;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Partial-match semantics of the sequence tracker: advancement, completion,
 * re-anchoring, span expiry, and stale eviction.
 */
final class SequenceTrackerTest {

    private static final UUID SUBJECT = UUID.randomUUID();

    private static EventFacts of(final EventKind kind) {
        return new EventFacts(kind, SUBJECT, "Steve", Map.of());
    }

    private static final SequenceTracker.StepMatcher CHAT_THEN_BREAK =
            (step, facts) -> facts.kind() == (step == 0 ? EventKind.CHAT : EventKind.BREAK);

    @Test
    void orderedStepsCompleteAndReset() {
        final SequenceTracker tracker = new SequenceTracker();
        assertFalse(tracker.advance("k", 2, 30_000L, of(EventKind.CHAT),
                CHAT_THEN_BREAK, 1_000L));
        assertEquals(1, tracker.stepOf("k"));
        assertTrue(tracker.advance("k", 2, 30_000L, of(EventKind.BREAK),
                CHAT_THEN_BREAK, 2_000L));
        assertEquals(0, tracker.stepOf("k"));
    }

    @Test
    void nonAdvancingFirstStepEventReanchors() {
        final SequenceTracker tracker = new SequenceTracker();
        tracker.advance("k", 2, 30_000L, of(EventKind.CHAT), CHAT_THEN_BREAK, 1_000L);
        // A second chat does not satisfy step 1 but matches step 0 again.
        assertFalse(tracker.advance("k", 2, 30_000L, of(EventKind.CHAT),
                CHAT_THEN_BREAK, 5_000L));
        assertEquals(1, tracker.stepOf("k"));
        assertTrue(tracker.advance("k", 2, 30_000L, of(EventKind.BREAK),
                CHAT_THEN_BREAK, 6_000L));
    }

    @Test
    void agedPartialNeverFires() {
        final SequenceTracker tracker = new SequenceTracker();
        tracker.advance("k", 2, 30_000L, of(EventKind.CHAT), CHAT_THEN_BREAK, 1_000L);
        // The completing event arrives beyond the span; the partial is
        // discarded before matching and the event does not re-anchor.
        assertFalse(tracker.advance("k", 2, 30_000L, of(EventKind.BREAK),
                CHAT_THEN_BREAK, 40_000L));
        assertEquals(0, tracker.stepOf("k"));
    }

    @Test
    void partitionsAreIndependent() {
        final SequenceTracker tracker = new SequenceTracker();
        tracker.advance("a", 2, 30_000L, of(EventKind.CHAT), CHAT_THEN_BREAK, 1_000L);
        assertEquals(0, tracker.stepOf("b"));
        assertFalse(tracker.advance("b", 2, 30_000L, of(EventKind.BREAK),
                CHAT_THEN_BREAK, 1_000L));
    }

    @Test
    void staleEvictionReclaimsPartitions() {
        final SequenceTracker tracker = new SequenceTracker();
        tracker.advance("k", 2, 30_000L, of(EventKind.CHAT), CHAT_THEN_BREAK, 1_000L);
        assertEquals(1, tracker.size());
        assertEquals(1, tracker.evictStale(10_000_000L, 3_600_000L));
        assertEquals(0, tracker.size());
    }
}