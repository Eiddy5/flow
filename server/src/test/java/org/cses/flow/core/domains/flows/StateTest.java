package org.cses.flow.core.domains.flows;

import org.cses.flow.core.exceptions.WorkflowException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateTest {

    @Test
    void createdShouldRecordTheInitialStateAtTheCurrentTime() {
        long before = System.currentTimeMillis();

        State state = State.created();

        long after = System.currentTimeMillis();
        assertEquals(State.Type.CREATED, state.current());
        assertEquals(1, state.history().size());
        assertEquals(
            State.Type.CREATED,
            state.history().getFirst().state()
        );
        assertTrue(state.history().getFirst().date() >= before);
        assertTrue(state.history().getFirst().date() <= after);
        assertTrue(state.isActive());
        assertFalse(state.isPaused());
        assertFalse(state.isTerminal());
        assertThrows(
            UnsupportedOperationException.class,
            () -> state.history().add(
                State.History.rehydrate(State.Type.RUNNING, after)
            )
        );
    }

    @Test
    void withStateShouldAppendHistoryWithoutChangingThePreviousValue() {
        State created = State.created();
        State running = created.withState(State.Type.RUNNING);
        State paused = running.paused();
        State resumed = paused.running();
        State completed = resumed.complete();

        assertEquals(State.Type.CREATED, created.current());
        assertEquals(1, created.history().size());
        assertEquals(
            List.of(
                State.Type.CREATED,
                State.Type.RUNNING,
                State.Type.PAUSED,
                State.Type.RUNNING,
                State.Type.COMPLETED
            ),
            completed.history().stream()
                .map(State.History::state)
                .toList()
        );
        assertEquals(State.Type.COMPLETED, completed.current());
        assertTrue(completed.isTerminal());
    }

    @Test
    void shortcutsShouldUseTheSameGenericTransition() {
        State failed = State.created()
            .running()
            .fail();
        State terminatedWhilePaused = State.created()
            .running()
            .paused()
            .terminate();

        assertEquals(State.Type.TERMINATED, failed.current());
        assertEquals(State.Type.TERMINATED, terminatedWhilePaused.current());
        assertEquals(3, failed.history().size());
        assertEquals(4, terminatedWhilePaused.history().size());
    }

    @Test
    void rehydrateShouldRestoreTheExactHistoryWithoutAppending() {
        State restored = State.rehydrate(
            State.Type.PAUSED,
            List.of(
                State.History.rehydrate(State.Type.CREATED, 100L),
                State.History.rehydrate(State.Type.RUNNING, 200L),
                State.History.rehydrate(State.Type.PAUSED, 300L)
            )
        );

        assertEquals(State.Type.PAUSED, restored.current());
        assertEquals(3, restored.history().size());
        assertEquals(300L, restored.history().getLast().date());
    }

    @Test
    void rejectsInvalidHistoryAndIllegalGenericTransitions() {
        assertThrows(
            IllegalArgumentException.class,
            () -> State.rehydrate(State.Type.CREATED, List.of())
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> State.rehydrate(
                State.Type.RUNNING,
                List.of(
                    State.History.rehydrate(State.Type.RUNNING, 100L)
                )
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> State.rehydrate(
                State.Type.RUNNING,
                List.of(
                    State.History.rehydrate(State.Type.CREATED, 100L)
                )
            )
        );
        assertThrows(
            WorkflowException.class,
            () -> State.created().withState(State.Type.PAUSED)
        );
        assertThrows(
            WorkflowException.class,
            () -> State.created().running().running()
        );
        assertThrows(
            WorkflowException.class,
            () -> State.created().running().paused().complete()
        );
        assertThrows(
            WorkflowException.class,
            () -> State.created().running().complete().running()
        );
    }
}
