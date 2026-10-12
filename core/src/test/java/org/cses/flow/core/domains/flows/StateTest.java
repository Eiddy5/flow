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

    /** 验证重建拒绝不一致历史，迁移入口拒绝非法路线及重复同状态。 */
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
            () -> State.created().running().skipped().running()
        );
    }

    /** 验证通用超集保留重试到期和成功或警告祖先重开的完整历史。 */
    @Test
    void allowsRetryAndControlledAncestorReopeningInTheGenericSuperset() {
        State retry = State.created().running()
            .withState(State.Type.RETRY_WAIT).withState(State.Type.CREATED)
            .running().success();
        assertEquals(List.of(State.Type.CREATED, State.Type.RUNNING,
            State.Type.RETRY_WAIT, State.Type.CREATED, State.Type.RUNNING,
            State.Type.SUCCESS), retry.history().stream().map(State.History::state).toList());

        for (State.Type completed : List.of(State.Type.SUCCESS, State.Type.WARNING)) {
            State reopened = State.created().running().withState(completed).running();
            assertEquals(State.Type.RUNNING, reopened.current());
            assertEquals(4, reopened.history().size());
        }
    }

    /** 验证历史首尾一致时仍拒绝非法跳转、重复状态及失败后重开。 */
    @Test
    void rejectsIllegalAdjacentStatesWhenRehydrating() {
        assertThrows(IllegalArgumentException.class, () -> State.rehydrate(
            State.Type.PAUSED, List.of(State.History.rehydrate(State.Type.CREATED, 100L),
                State.History.rehydrate(State.Type.PAUSED, 200L))));
        assertThrows(IllegalArgumentException.class, () -> State.rehydrate(
            State.Type.RUNNING, List.of(State.History.rehydrate(State.Type.CREATED, 100L),
                State.History.rehydrate(State.Type.RUNNING, 200L),
                State.History.rehydrate(State.Type.RUNNING, 300L))));
        assertThrows(IllegalArgumentException.class, () -> State.rehydrate(
            State.Type.RUNNING, List.of(State.History.rehydrate(State.Type.CREATED, 100L),
                State.History.rehydrate(State.Type.RUNNING, 200L),
                State.History.rehydrate(State.Type.FAILED, 300L),
                State.History.rehydrate(State.Type.RUNNING, 400L))));
    }

    /** 验证系统时间回拨不影响按列表顺序重建合法迁移。 */
    @Test
    void historyOrderDoesNotRequireIncreasingWallClockValues() {
        State restored = State.rehydrate(State.Type.RUNNING, List.of(
            State.History.rehydrate(State.Type.CREATED, 200L),
            State.History.rehydrate(State.Type.RUNNING, 100L)));
        assertEquals(100L, restored.history().getLast().date());
    }
}
