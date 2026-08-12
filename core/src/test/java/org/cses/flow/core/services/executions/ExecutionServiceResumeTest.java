package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.exceptions.WorkflowException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionServiceResumeTest {

    @Test
    void resumesPersistedPauseThroughExecutionLifecycleEntry() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "execution-resume-flow",
                    "Execution 生命周期恢复",
                    true
                )
            );
            Execution started = fixture.startAndAwait(flow);
            String pauseTaskRunId = started.taskRuns().getFirst().id();

            fixture.restartServer();
            Execution completed = fixture.executionService().resume(
                fixture.session(),
                started.id(),
                pauseTaskRunId,
                Map.of("decision", "APPROVED")
            );

            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(
                List.of(
                    State.Type.SUCCESS,
                    State.Type.SUCCESS,
                    State.Type.SUCCESS
                ),
                completed.taskRuns().stream().map(taskRun -> taskRun.state().current()).toList()
            );
            assertEquals(
                Map.of("decision", "APPROVED"),
                completed.taskRuns().getFirst().outputs()
            );
            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().resume(
                    fixture.session(),
                    completed.id(),
                    pauseTaskRunId,
                    Map.of("decision", "APPROVED")
                )
            );
        }
    }

    @Test
    void rejectsInvalidResumeWithoutChangingPausedExecution() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "execution-invalid-resume-flow",
                    "非法恢复保持暂停",
                    true
                )
            );
            Execution started = fixture.startAndAwait(flow);
            String pauseTaskRunId = started.taskRuns().getFirst().id();

            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().resume(
                    fixture.sessionFor("other-tenant"),
                    started.id(),
                    pauseTaskRunId,
                    Map.of("decision", "APPROVED")
                )
            );
            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().resume(
                    fixture.session(),
                    started.id(),
                    pauseTaskRunId,
                    null
                )
            );
            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().resume(
                    fixture.session(),
                    started.id(),
                    pauseTaskRunId,
                    Map.of("undeclared", "value")
                )
            );

            Execution unchanged = fixture.executionService().execution(
                fixture.session(),
                started.id()
            ).orElseThrow();
            assertEquals(State.Type.PAUSED, unchanged.state().current());
            assertEquals(
                State.Type.PAUSED,
                unchanged.taskRuns().getFirst().state().current()
            );

            fixture.executionService().cancel(
                fixture.session(),
                started.id()
            );
        }
    }
}
