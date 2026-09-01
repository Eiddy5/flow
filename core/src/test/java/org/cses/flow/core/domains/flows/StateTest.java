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
        State restarted = paused.restarted();
        State resumed = restarted.running();
        State succeeded = resumed.success();

        assertEquals(State.Type.CREATED, created.current());
        assertEquals(1, created.history().size());
        assertEquals(
            List.of(
                State.Type.CREATED,
                State.Type.RUNNING,
                State.Type.PAUSED,
                State.Type.RESTARTED,
                State.Type.RUNNING,
                State.Type.SUCCESS
            ),
            succeeded.history().stream()
                .map(State.History::state)
                .toList()
        );
        assertEquals(State.Type.SUCCESS, succeeded.current());
        assertTrue(succeeded.isTerminal());
    }

    @Test
    void shortcutsShouldUseTheSameGenericTransition() {
        State failed = State.created()
            .running()
            .failed();
        State skipped = State.created()
            .running()
            .skipped();
        State killedWhilePaused = State.created()
            .running()
            .paused()
            .killing()
            .killed();

        assertEquals(State.Type.FAILED, failed.current());
        assertEquals(State.Type.SKIPPED, skipped.current());
        assertEquals(State.Type.KILLED, killedWhilePaused.current());
        assertEquals(3, failed.history().size());
        assertEquals(
            List.of(
                State.Type.CREATED,
                State.Type.RUNNING,
                State.Type.SKIPPED
            ),
            skipped.history().stream()
                .map(State.History::state)
                .toList()
        );
        assertTrue(skipped.isTerminal());
        assertEquals(5, killedWhilePaused.history().size());
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
            () -> State.created().running().paused().success()
        );
        assertThrows(
            WorkflowException.class,
            () -> State.created().running().success().running()
        );
        assertThrows(
            WorkflowException.class,
            () -> State.created().running().skipped().running()
        );
    }
}
