package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExecutionAsyncStartIntegrationTest {

    private static final Map<String, Object> AUTO_TASK =
        Map.of("flow.uc03.auto-task", true);

    @Test
    void returnsAfterQueueAcceptanceBeforeTaskCompletion()
        throws InterruptedException {
        Uc03AutomaticTask.blockNextRun();
        try (WorkflowUcFixture fixture =
                 WorkflowUcFixture.openWithProperties(AUTO_TASK)) {
            Flow flow = fixture.deploy("""
                key: execution-async-acceptance
                description: start returns before task completion
                tasks:
                  - key: target-block
                    type: org.cses.flow.core.services.executions.Uc03AutomaticTask
                """);

            Execution accepted = fixture.startCreated(flow);

            assertEquals(State.Type.CREATED, accepted.state().current());
            assertTrue(Uc03AutomaticTask.awaitBlockingRun());

            Uc03AutomaticTask.releaseBlockingRun();
            assertEquals(
                State.Type.SUCCESS,
                fixture.awaitStable(accepted).state().current()
            );
        } finally {
            Uc03AutomaticTask.releaseBlockingRun();
        }
    }

    @Test
    void continuingPendingExecutionReturnsAfterQueueAcceptance()
        throws InterruptedException {
        Uc03AutomaticTask.blockNextRun();
        try (WorkflowUcFixture fixture =
                 WorkflowUcFixture.openWithProperties(AUTO_TASK)) {
            Flow flow = fixture.deploy("""
                key: execution-async-continue
                description: continue returns before task completion
                tasks:
                  - key: target-block
                    type: org.cses.flow.core.services.executions.Uc03AutomaticTask
                """);
            Execution pending = fixture.executionService().createPending(
                fixture.session(),
                flow.key()
            );

            Execution accepted = fixture.executionService()
                .continueExecution(fixture.session(), pending.id());

            assertEquals(State.Type.CREATED, accepted.state().current());
            assertTrue(Uc03AutomaticTask.awaitBlockingRun());

            Uc03AutomaticTask.releaseBlockingRun();
            assertEquals(
                State.Type.SUCCESS,
                fixture.awaitStable(accepted).state().current()
            );
        } finally {
            Uc03AutomaticTask.releaseBlockingRun();
        }
    }

    @Test
    void returnsAfterResumeQueueAcceptanceBeforeContinuationCompletes()
        throws InterruptedException {
        Uc03AutomaticTask.blockNextRun();
        try (WorkflowUcFixture fixture =
                 WorkflowUcFixture.openWithProperties(AUTO_TASK)) {
            Flow flow = fixture.deploy("""
                key: execution-async-resume
                description: resume returns before continuation completion
                tasks:
                  - key: wait-confirmation
                    type: org.cses.flow.extensions.flow.Pause
                    pause:
                      key: create-confirmation
                      type: org.cses.flow.extensions.tasks.AutomaticTask
                    resume:
                      - key: decision
                        type: STRING
                  - key: target-block
                    type: org.cses.flow.core.services.executions.Uc03AutomaticTask
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
            assertTrue(Uc03AutomaticTask.awaitBlockingRun());

            Uc03AutomaticTask.releaseBlockingRun();
            assertEquals(
                State.Type.SUCCESS,
                fixture.awaitStable(accepted).state().current()
            );
        } finally {
            Uc03AutomaticTask.releaseBlockingRun();
        }
    }
}
