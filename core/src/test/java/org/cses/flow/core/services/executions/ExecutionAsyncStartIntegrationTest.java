package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExecutionAsyncStartIntegrationTest {

    private static final Map<String, Object> TEST_TASK =
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
}
