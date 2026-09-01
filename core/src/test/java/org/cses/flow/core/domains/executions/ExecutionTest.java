package org.cses.flow.core.domains.executions;

import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.exceptions.WorkflowException;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExecutionTest {

    @Test
    void createsAnExecutionWithACallerProvidedStableId() {
        Execution execution = Execution.create(
            "execution-stable-1",
            session("execution-domain-company"),
            "execution-domain-flow",
            3,
            Map.of()
        );

        assertEquals("execution-stable-1", execution.id());
        assertEquals("execution-domain-company", execution.companyId());
        assertEquals("execution-domain-flow", execution.flowKey());
        assertEquals(3L, execution.flowVersion());
    }

    @Test
    void storesConfirmedFlowInputsOnTheExecutionAggregate() {
        Execution execution = Execution.create(
            null,
            session("execution-input-company"),
            "execution-input-flow",
            1,
            Map.of(
                "amount", 1200,
                "metadata", Map.of("source", "api")
            )
        );

        assertEquals(
            Map.of(
                "amount", 1200,
                "metadata", Map.of("source", "api")
            ),
            execution.inputs()
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> execution.inputs().put("amount", 1)
        );
    }

    @Test
    void createTaskRunShouldGenerateStableChildIdentity() {
        Execution execution = Execution.create(
            null,
            session("execution-domain-company"),
            "execution-domain-flow",
            1,
            Map.of()
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
            null,
            session("execution-state-company"),
            "execution-state-flow",
            1,
            Map.of()
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

        execution.pauseTaskRun(taskRun.id());
        execution.pause();

        assertTrue(execution.state().is(State.Type.PAUSED));
        assertTrue(taskRun.state().is(State.Type.PAUSED));

        execution.resumeTaskRun(taskRun.id(), Map.of());
        assertTrue(execution.state().is(State.Type.RESTARTED));
        assertTrue(taskRun.state().is(State.Type.RUNNING));
        execution.restart();
        execution.succeedTaskRun(taskRun.id(), Map.of());
        execution.succeed();

        assertTrue(execution.state().is(State.Type.SUCCESS));
        assertTrue(taskRun.state().is(State.Type.SUCCESS));
        assertEquals(
            List.of(
                State.Type.CREATED,
                State.Type.RUNNING,
                State.Type.PAUSED,
                State.Type.RESTARTED,
                State.Type.RUNNING,
                State.Type.SUCCESS
            ),
            history(execution.state())
        );
        assertEquals(
            List.of(
                State.Type.CREATED,
                State.Type.RUNNING,
                State.Type.PAUSED,
                State.Type.RUNNING,
                State.Type.SUCCESS
            ),
            history(taskRun.state())
        );
    }

    @Test
    void executionAndTaskRunShouldProtectTheirOwnRoutes() {
        Execution execution = Execution.create(
            null,
            session("execution-route-company"),
            "execution-route-flow",
            1,
            Map.of()
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
            () -> execution.succeedTaskRun(taskRun.id(), Map.of())
        );
        assertThrows(
            WorkflowException.class,
            () -> execution.skipTaskRun(taskRun.id())
        );
        assertEquals(State.Type.CREATED, taskRun.state().current());
    }

    @Test
    void skippedTaskRunIsTerminalWithoutOutputsOrError() {
        Execution execution = Execution.create(
            null,
            session("execution-skipped-company"),
            "execution-skipped-flow",
            1,
            Map.of()
        );
        execution.start();
        TaskRun taskRun = execution.createTaskRun(
            "execution-skipped-route",
            null,
            Map.of("decision", "not-selected")
        );
        execution.startTaskRun(taskRun.id());

        execution.skipTaskRun(taskRun.id());

        assertEquals(State.Type.SKIPPED, taskRun.state().current());
        assertEquals(
            List.of(
                State.Type.CREATED,
                State.Type.RUNNING,
                State.Type.SKIPPED
            ),
            history(taskRun.state())
        );
        assertTrue(taskRun.outputs().isEmpty());
        assertTrue(taskRun.error().isEmpty());
        assertFalse(taskRun.isUnfinished());

        execution.succeed();
        assertEquals(State.Type.SUCCESS, execution.state().current());
    }

    @Test
    void skippedTaskRunCannotBeARewindTarget() {
        Execution execution = Execution.create(
            null,
            session("execution-skipped-rewind-company"),
            "execution-skipped-rewind-flow",
            1,
            Map.of()
        );
        execution.start();
        TaskRun skipped = execution.createTaskRun(
            "execution-skipped-target",
            null,
            Map.of()
        );
        execution.startTaskRun(skipped.id());
        execution.skipTaskRun(skipped.id());
        TaskRun source = execution.createTaskRun(
            "execution-skipped-source",
            null,
            Map.of()
        );
        execution.startTaskRun(source.id());
        execution.pauseTaskRun(source.id());
        execution.pause();

        assertThrows(
            WorkflowException.class,
            () -> execution.rewindTaskRun(
                source.id(),
                skipped.id(),
                "cannot rewind to skipped route"
            )
        );
        assertEquals(State.Type.PAUSED, execution.state().current());
    }

    @Test
    void addTaskRunsShouldRejectAnInvalidBatchAtomically() {
        Execution execution = Execution.create(
            null,
            session("execution-batch-company"),
            "execution-batch-flow",
            1,
            Map.of()
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
    void loopIterationsAreDistinctOccurrencesOfTheSameTask() {
        Execution execution = Execution.create(
            null,
            session("loop-company"),
            "loop-flow",
            1,
            Map.of()
        );
        execution.start();
        TaskRun loop = execution.createTaskRun(
            "loop-task",
            null,
            Map.of()
        );
        execution.startTaskRun(loop.id());

        TaskRun first = TaskRun.create(
            "work-task",
            loop.id(),
            Map.of(),
            1
        );
        TaskRun second = TaskRun.create(
            "work-task",
            loop.id(),
            Map.of(),
            2
        );

        execution.addTaskRuns(List.of(first, second));

        assertEquals(2, execution.taskRunsForTask("work-task").size());
        assertEquals(
            first.id(),
            execution.taskRunForOccurrence(
                "work-task",
                loop.id(),
                1
            ).orElseThrow().id()
        );
    }

    @Test
    void iterationRequiresAPositiveValueAndAParent() {
        assertThrows(
            IllegalArgumentException.class,
            () -> TaskRun.create("task", "parent", Map.of(), 0)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> TaskRun.create("task", null, Map.of(), 1)
        );
    }

    @Test
    void completedRewindShouldExposeOnlyTheLatestFragmentResults() {
        Execution execution = Execution.create(
            null,
            session("rewind-effective-company"),
            "rewind-effective-flow",
            1,
            Map.of()
        );
        execution.start();

        TaskRun before = execution.createTaskRun(
            "before",
            null,
            Map.of()
        );
        execution.startTaskRun(before.id());
        execution.succeedTaskRun(before.id(), Map.of("value", "before"));

        TaskRun originalTarget = execution.createTaskRun(
            "target",
            null,
            Map.of()
        );
        execution.startTaskRun(originalTarget.id());
        execution.warnTaskRun(
            originalTarget.id(),
            Map.of("value", "obsolete-warning")
        );

        TaskRun originalSource = execution.createTaskRun(
            "approval",
            null,
            Map.of()
        );
        execution.startTaskRun(originalSource.id());
        execution.pauseTaskRun(originalSource.id());
        execution.pause();

        execution.rewindTaskRun(
            originalSource.id(),
            originalTarget.id(),
            "correct warning result"
        );
        execution.restart();

        TaskRun latestTarget = TaskRun.create(
            "target",
            null,
            Map.of(),
            null,
            1
        );
        execution.addTaskRuns(List.of(latestTarget));
        execution.startTaskRun(latestTarget.id());
        execution.succeedTaskRun(
            latestTarget.id(),
            Map.of("value", "latest-success")
        );

        TaskRun latestSource = TaskRun.create(
            "approval",
            null,
            Map.of(),
            null,
            1
        );
        execution.addTaskRuns(List.of(latestSource));
        execution.startTaskRun(latestSource.id());
        execution.pauseTaskRun(latestSource.id());
        execution.pause();
        execution.resumeTaskRun(latestSource.id(), Map.of());
        execution.restart();
        execution.succeedTaskRun(latestSource.id(), Map.of());

        List<TaskRun> effective = execution.effectiveTaskRuns();
        assertEquals(
            List.of(before.id(), latestTarget.id(), latestSource.id()),
            effective.stream().map(TaskRun::id).toList()
        );
        assertTrue(effective.stream().noneMatch(taskRun ->
            taskRun.state().is(State.Type.WARNING)
        ));
        assertEquals(
            "latest-success",
            effective.stream()
                .filter(taskRun -> taskRun.taskId().equals("target"))
                .findFirst()
                .orElseThrow()
                .outputs()
                .get("value")
        );
    }

    @Test
    void rehydrateShouldRejectPausedExecutionState() {
        State paused = State.rehydrate(
            State.Type.PAUSED,
            List.of(
                State.History.rehydrate(State.Type.CREATED, 100L),
                State.History.rehydrate(State.Type.RUNNING, 200L),
                State.History.rehydrate(State.Type.PAUSED, 300L)
            )
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> Execution.rehydrate(
                "execution-route-execution",
                "execution-route-company",
                ActorRef.create("execution-creator", "Execution creator"),
                100L,
                "execution-route-flow",
                1,
                Map.of(),
                paused,
                List.of()
            )
        );
    }

    @Test
    void rehydrateShouldRejectSkippedExecutionState() {
        State skipped = State.rehydrate(
            State.Type.SKIPPED,
            List.of(
                State.History.rehydrate(State.Type.CREATED, 100L),
                State.History.rehydrate(State.Type.RUNNING, 200L),
                State.History.rehydrate(State.Type.SKIPPED, 300L)
            )
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> Execution.rehydrate(
                "execution-skipped-execution",
                "execution-skipped-company",
                ActorRef.create("execution-creator", "Execution creator"),
                100L,
                "execution-skipped-flow",
                1,
                Map.of(),
                skipped,
                List.of()
            )
        );
    }

    private static List<State.Type> history(State state) {
        return state.history().stream()
            .map(State.History::state)
            .toList();
    }

    private static Session<User> session(String companyId) {
        User user = new User();
        user.setId("execution-creator");
        user.setName("Execution creator");
        user.setCompanyId(companyId);

        Session<User> session = new Session<>();
        session.setCompanyId(companyId);
        session.setUser(user);
        return session;
    }
}
