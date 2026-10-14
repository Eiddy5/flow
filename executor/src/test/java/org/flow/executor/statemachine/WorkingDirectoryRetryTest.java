package org.flow.executor.statemachine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.executions.TaskRunAttempt;
import org.flow.core.models.flows.FlowWithSource;
import org.flow.core.models.flows.State;
import org.flow.core.runners.WorkerTask;
import org.flow.core.runners.WorkerTaskResult;
import org.flow.core.utils.IdUtils;
import org.flow.executor.testkit.Executions;
import org.flow.executor.testkit.ExecutorTestHarness;
import org.flow.executor.testkit.Flows;
import org.flow.executor.testkit.ScriptedWorker;

import static org.flow.executor.testkit.HarnessAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The children of a WorkingDirectory only run inside it, on its worker, so retrying a failed child means running the
 * whole WorkingDirectory again.
 */
class WorkingDirectoryRetryTest {

    private static final Instant AFTER_ANY_RETRY_DELAY = Instant.parse("2100-01-01T00:00:00Z");

    private final ExecutorTestHarness harness = ExecutorTestHarness.create();
    private final List<WorkerTask> dispatched = new ArrayList<>();
    private final FlowWithSource flow = Flows.yaml("""
        id: working-directory-retry
        namespace: org.flow.tests
        concurrency:
          limit: 1
        retry:
          type: constant
          interval: PT1S
          maxAttempts: 2
        tasks:
          - id: working-directory
            type: org.flow.plugin.core.flow.WorkingDirectory
            tasks:
              - id: generate
                type: org.flow.plugin.core.log.Log
                message: generate
              - id: ingest
                type: org.flow.plugin.core.log.Log
                message: ingest
        """);

    @Test
    void shouldRunTheWorkingDirectoryAgainWhenAChildIsRetried() {
        // Given: the first child fails, so the worker stops the WorkingDirectory there
        harness.registerFlow(flow);
        Execution created = Executions.created(flow);
        harness.run(created, recordingWorker(State.Type.FAILED));

        // When: the retry fires and the second run of the WorkingDirectory succeeds
        harness.tickExecutionDelays(AFTER_ANY_RETRY_DELAY);
        harness.run(List.of(), recordingWorker(State.Type.SUCCESS));
        harness.run(List.of(childResult(dispatched.getLast(), "ingest", State.Type.SUCCESS)), recordingWorker(State.Type.SUCCESS));

        // Then
        assertThat(dispatched).extracting(task -> task.getTaskRun().getTaskId()).containsExactly("working-directory", "working-directory");
        assertThat(harness).hasExecutionInState(created, State.Type.SUCCESS).hasRunning(flow, 0);
    }

    @Test
    void shouldFailWhenTheWorkingDirectoryRetriesAreExhausted() {
        // Given
        harness.registerFlow(flow);
        Execution created = Executions.created(flow);
        harness.run(created, recordingWorker(State.Type.FAILED));

        // When: every run of the WorkingDirectory fails on its first child
        for (int i = 0; i < 3; i++) {
            harness.tickExecutionDelays(AFTER_ANY_RETRY_DELAY);
            harness.run(List.of(), recordingWorker(State.Type.FAILED));
        }

        // Then
        assertThat(dispatched).extracting(task -> task.getTaskRun().getTaskId()).containsExactly("working-directory", "working-directory");
        assertThat(harness).hasExecutionInState(created, State.Type.FAILED).hasRunning(flow, 0);
    }

    private ScriptedWorker recordingWorker(State.Type generateState) {
        return task ->
        {
            dispatched.add(task);
            return childResult(task, "generate", generateState);
        };
    }

    private static WorkerTaskResult childResult(WorkerTask workingDirectory, String taskId, State.Type state) {
        TaskRun child = workingDirectory.getTaskRun().toBuilder()
            .id(IdUtils.create())
            .taskId(taskId)
            .parentTaskRunId(workingDirectory.getTaskRun().getId())
            .attempts(List.of(TaskRunAttempt.builder().workerId("worker").state(new State().withState(state)).build()))
            .state(new State().withState(state))
            .build();
        return new WorkerTaskResult(child);
    }
}
