package org.cses.flow.core.domains.executions;

import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.exceptions.WorkflowException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExecutionTest {

    @Test
    void createTaskRunShouldGenerateStableChildIdentity() {
        Execution execution = Execution.create(
            "execution-domain-company",
            "execution-domain-flow",
            1
        );
        execution.start();

        TaskRun first = execution.createTaskRun(
            "execution-domain-task-1",
            null,
            Map.of("request", "A-1")
        );
        TaskRun second = execution.createTaskRun(
            "execution-domain-task-2",
            first.id(),
            Map.of("request", "A-2")
        );

        assertFalse(first.id().isBlank());
        assertFalse(second.id().isBlank());
        assertNotEquals(first.id(), second.id());
        assertEquals(
            first.id(),
            execution.taskRuns().getFirst().id()
        );
        assertEquals(
            second.id(),
            execution.taskRuns().getLast().id()
        );
    }

    @Test
    void executionAndTaskRunUseTheSharedWorkflowState() {
        Execution execution = Execution.create(
            "execution-state-company",
            "execution-state-flow",
            1
        );

        assertTrue(execution.state().is(State.Type.CREATED));
        assertEquals(
            List.of(State.Type.CREATED),
            history(execution.state())
        );

        execution.start();
        TaskRun taskRun = execution.createTaskRun(
            "execution-state-task",
            null,
            Map.of()
        );

        assertTrue(execution.state().is(State.Type.RUNNING));
        assertTrue(taskRun.state().is(State.Type.CREATED));

        execution.startTaskRun(taskRun.id());

        assertTrue(execution.state().is(State.Type.RUNNING));
        assertTrue(taskRun.state().is(State.Type.RUNNING));

        execution.waitTaskRun(taskRun.id());
        execution.enterWaiting();

        assertTrue(execution.state().is(State.Type.WAITING));
        assertTrue(taskRun.state().is(State.Type.WAITING));

        execution.resumeTaskRun(taskRun.id(), Map.of());
        execution.complete();

        assertTrue(execution.state().is(State.Type.COMPLETED));
        assertTrue(taskRun.state().is(State.Type.COMPLETED));
        assertEquals(
            List.of(
                State.Type.CREATED,
                State.Type.RUNNING,
                State.Type.WAITING,
                State.Type.RUNNING,
                State.Type.COMPLETED
            ),
            history(execution.state())
        );
        assertEquals(
            List.of(
                State.Type.CREATED,
                State.Type.RUNNING,
                State.Type.WAITING,
                State.Type.COMPLETED
            ),
            history(taskRun.state())
        );
    }

    @Test
    void executionAndTaskRunShouldProtectTheirOwnRoutes() {
        Execution execution = Execution.create(
            "execution-route-company",
            "execution-route-flow",
            1
        );

        assertThrows(
            WorkflowException.class,
            () -> execution.createTaskRun(
                "execution-route-task",
                null,
                Map.of()
            )
        );

        execution.start();
        TaskRun taskRun = execution.createTaskRun(
            "execution-route-task",
            null,
            Map.of()
        );

        assertThrows(
            WorkflowException.class,
            () -> execution.completeTaskRun(taskRun.id(), Map.of())
        );
        assertEquals(State.Type.CREATED, taskRun.state().current());
    }

    @Test
    void addTaskRunsShouldRejectAnInvalidBatchAtomically() {
        Execution execution = Execution.create(
            "execution-batch-company",
            "execution-batch-flow",
            1
        );
        execution.start();
        TaskRun first = TaskRun.create(
            "execution-batch-task",
            null,
            Map.of()
        );
        TaskRun duplicateTask = TaskRun.create(
            "execution-batch-task",
            null,
            Map.of()
        );

        assertThrows(
            WorkflowException.class,
            () -> execution.addTaskRuns(List.of(first, duplicateTask))
        );

        assertTrue(execution.taskRuns().isEmpty());
    }

    @Test
    void rehydrateShouldRejectRoutesThatOnlyTheGenericStateAllows() {
        State taskRunRestart = State.rehydrate(
            State.Type.RUNNING,
            List.of(
                State.History.rehydrate(State.Type.CREATED, 100L),
                State.History.rehydrate(State.Type.RUNNING, 200L),
                State.History.rehydrate(State.Type.WAITING, 300L),
                State.History.rehydrate(State.Type.RUNNING, 400L)
            )
        );
        State executionCompletesWhileWaiting = State.rehydrate(
            State.Type.COMPLETED,
            List.of(
                State.History.rehydrate(State.Type.CREATED, 100L),
                State.History.rehydrate(State.Type.RUNNING, 200L),
                State.History.rehydrate(State.Type.WAITING, 300L),
                State.History.rehydrate(State.Type.COMPLETED, 400L)
            )
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> TaskRun.rehydrate(
                "execution-route-task-run",
                "execution-route-task",
                null,
                Map.of(),
                taskRunRestart,
                Map.of(),
                null
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> Execution.rehydrate(
                "execution-route-execution",
                "execution-route-company",
                "execution-route-flow",
                1,
                executionCompletesWhileWaiting,
                0,
                List.of()
            )
        );
    }

    private static List<State.Type> history(State state) {
        return state.history().stream()
            .map(State.History::state)
            .toList();
    }
}
