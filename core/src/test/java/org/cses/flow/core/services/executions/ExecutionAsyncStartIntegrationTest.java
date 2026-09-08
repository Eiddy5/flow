package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.exceptions.WorkflowException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionAsyncStartIntegrationTest {

    private static Map<String, Object> TEST_TASK =
        Map.of("flow.test.workflow-task", true);

    @Test
    void returnsAfterQueueAcceptanceBeforeTaskCompletion()
        throws InterruptedException {
        TestWorkflowTask.blockNextRun();
        try (WorkflowUcFixture fixture =
                 WorkflowUcFixture.openWithProperties(TEST_TASK)) {
            Flow flow = fixture.deploy("""
                key: execution-async-acceptance
                description: start returns before task completion
                tasks:
                  - key: target-block
                    type: org.cses.flow.core.services.executions.TestWorkflowTask
                """);

            Execution accepted = fixture.startCreated(flow);

            assertEquals(State.Type.CREATED, accepted.state().current());
            assertTrue(TestWorkflowTask.awaitBlockingRun());

            TestWorkflowTask.releaseBlockingRun();
            assertEquals(
                State.Type.SUCCESS,
                fixture.awaitStable(accepted).state().current()
            );
        } finally {
            TestWorkflowTask.releaseBlockingRun();
        }
    }

    @Test
    void returnsAfterResumeQueueAcceptanceBeforeContinuationCompletes()
        throws InterruptedException {
        TestWorkflowTask.blockNextRun();
        try (WorkflowUcFixture fixture =
                 WorkflowUcFixture.openWithProperties(TEST_TASK)) {
            Flow flow = fixture.deploy("""
                key: execution-async-resume
                description: resume returns before continuation completion
                tasks:
                  - key: wait-confirmation
                    type: org.cses.flow.extensions.flow.Pause
                    pause:
                      key: create-confirmation
                      type: org.cses.flow.extensions.log.Log
                      message: "创建确认步骤"
                    resume:
                      - key: decision
                        type: STRING
                    outputs:
                      - key: decision
                        type: STRING
                  - key: target-block
                    type: org.cses.flow.core.services.executions.TestWorkflowTask
                """);
            Execution started = fixture.startAndAwait(flow);
            WorkflowUcFixture.PausedTaskRunRef paused =
                fixture.waitingForExecution(started.id());

            Execution accepted = fixture.executionService().resume(
                fixture.session(),
                paused.executionId(),
                paused.taskRunId(),
                Map.of("decision", "APPROVED")
            );

            assertEquals(State.Type.PAUSED, accepted.state().current());
            assertTrue(TestWorkflowTask.awaitBlockingRun());

            TestWorkflowTask.releaseBlockingRun();
            assertEquals(
                State.Type.SUCCESS,
                fixture.awaitStable(accepted).state().current()
            );
        } finally {
            TestWorkflowTask.releaseBlockingRun();
        }
    }

    /**
     * Accepts an automatic decision during a mandatory Pause pre-action while
     * keeping strict resume validation. After release, the decision reaches the
     * continuation and the pre-action has exactly one actual invocation.
     *
     * @throws InterruptedException while awaiting the controlled Worker start
     */
    @Test
    void acceptsEarlyResumeWithoutRepeatingPausePreparation()
        throws InterruptedException {
        TestWorkflowTask.blockNextRun();
        try (WorkflowUcFixture fixture =
                 WorkflowUcFixture.openWithProperties(TEST_TASK)) {
            Flow flow = fixture.deploy("""
                key: execution-early-resume
                description: automatic decision waits for mandatory preparation
                tasks:
                  - key: wait-confirmation
                    type: org.cses.flow.extensions.flow.Pause
                    pause:
                      key: target-block
                      type: org.cses.flow.core.services.executions.TestWorkflowTask
                    resume:
                      - key: decision
                        type: STRING
                    outputs:
                      - key: decision
                        type: STRING
                  - key: record-result
                    type: org.cses.flow.extensions.log.Log
                    message: "test step"
                """);
            String pauseTaskId = flow.tasks().getFirst().id();
            String nextTaskId = flow.tasks().getLast().id();
            Execution started;
            try {
                started = fixture.startCreated(flow);
                assertTrue(TestWorkflowTask.awaitBlockingRun());
                Execution running = fixture.executionService().execution(
                    fixture.session(), started.id()
                ).orElseThrow();
                TaskRun pause = running.taskRunsForTask(pauseTaskId).getFirst();
                assertEquals(State.Type.RUNNING, running.state().current());
                assertEquals(State.Type.RUNNING, pause.state().current());
                assertThrows(WorkflowException.class, () ->
                    fixture.executionService().resume(
                        fixture.session(), running.id(), pause.id(),
                        Map.of("decision", "APPROVED")
                    )
                );

                Execution accepted = assertTimeoutPreemptively(
                    Duration.ofSeconds(5),
                    () -> fixture.executionService().resumeWhenPaused(
                        fixture.session(), running.id(), pause.id(),
                        Map.of("decision", "APPROVED")
                    )
                );

                assertEquals(
                    State.Type.RUNNING,
                    accepted.requireTaskRun(pause.id()).state().current()
                );
                assertTrue(accepted.taskRunsForTask(nextTaskId).isEmpty());
                assertEquals(1, TestWorkflowTask.blockingRunCount());
            } finally {
                TestWorkflowTask.releaseBlockingRun();
            }

            Execution completed = fixture.awaitExecution(
                fixture.session(), started.id(), Execution::isTerminal
            );
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(
                Map.of("decision", "APPROVED"),
                completed.taskRunsForTask(pauseTaskId).getFirst().outputs()
            );
            assertEquals(
                Map.of("outputs", Map.of("wait-confirmation",
                    Map.of("decision", "APPROVED"))),
                completed.taskRunsForTask(nextTaskId).getFirst().inputs()
            );
            assertEquals(3, completed.taskRuns().size());
            assertTrue(completed.taskRuns().stream().allMatch(taskRun ->
                taskRun.state().is(State.Type.SUCCESS)
            ));
            assertEquals(1, TestWorkflowTask.blockingRunCount());
            assertTrue(completed.activeTaskRuns().isEmpty());
            assertTrue(completed.pausedTaskRuns().isEmpty());
        }
    }
}
