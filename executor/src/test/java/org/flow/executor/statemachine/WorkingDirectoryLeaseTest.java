package org.flow.executor.statemachine;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.ExecutionKilled;
import org.flow.core.models.executions.ExecutionKilledExecution;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.flows.FlowWithSource;
import org.flow.core.models.flows.State;
import org.flow.core.runners.NoTransactionContext;
import org.flow.core.runners.WorkerTask;
import org.flow.core.runners.WorkerTaskResult;
import org.flow.core.utils.IdUtils;
import org.flow.executor.testkit.Executions;
import org.flow.executor.testkit.ExecutorTestHarness;
import org.flow.executor.testkit.Flows;
import org.flow.executor.testkit.Results;
import org.flow.executor.testkit.ScriptedWorker;
import org.flow.plugin.core.flow.WorkingDirectory;

import static org.flow.executor.testkit.HarnessAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A WorkingDirectory reports the results of its children only, so the executor has to release its worker job lease
 * whatever path ended it.
 */
class WorkingDirectoryLeaseTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final ExecutorTestHarness harness = ExecutorTestHarness.create();

    @Test
    void shouldReleaseWorkingDirectoryLeaseWhenItsChildrenEnded() {
        // Given
        FlowWithSource flow = Flows.of(workingDirectory());
        harness.registerFlow(flow);
        Execution created = Executions.created(flow);
        AtomicReference<String> workingDirectoryTaskRunId = new AtomicReference<>();

        // When: the worker runs the WorkingDirectory and reports its child only
        harness.run(created, task ->
        {
            workingDirectoryTaskRunId.set(task.getTaskRun().getId());
            return Results.success(childOf(task), T0);
        });

        // Then
        assertThat(harness).hasExecutionInState(created, State.Type.SUCCESS);
        verify(harness.workerJobRunningStateStore()).deleteByKey(NoTransactionContext.INSTANCE, workingDirectoryTaskRunId.get());
    }

    @Test
    void shouldReleaseWorkingDirectoryLeaseWhenItsExecutionIsKilled() {
        // Given: the WorkingDirectory is dispatched, then its execution is killed
        FlowWithSource flow = Flows.of(workingDirectory());
        harness.registerFlow(flow);
        Execution created = Executions.created(flow);
        harness.step(created);
        harness.step(
            ExecutionKilledExecution.builder()
                .tenantId(created.getTenantId())
                .executionId(created.getId())
                .state(ExecutionKilled.State.REQUESTED)
                .isOnKillCascade(false)
                .build()
        );
        AtomicReference<String> workingDirectoryTaskRunId = new AtomicReference<>();

        // When: the worker reports its child as KILLED, which kills the WorkingDirectory as its parent
        harness.run(List.of(), task ->
        {
            workingDirectoryTaskRunId.set(task.getTaskRun().getId());
            return Results.killed(childOf(task), T0);
        });

        // Then
        assertThat(harness).hasExecutionInState(created, State.Type.KILLED);
        verify(harness.workerJobRunningStateStore()).deleteByKey(NoTransactionContext.INSTANCE, workingDirectoryTaskRunId.get());
    }

    @Test
    void shouldReleaseWorkingDirectoryLeaseWhenAFailFastParentEndsIt() {
        // Given
        FlowWithSource flow = Flows.yaml("""
            id: fail-fast
            namespace: org.flow.tests
            tasks:
              - id: parallel
                type: org.flow.plugin.core.flow.Parallel
                onChildFailure: FAIL
                tasks:
                  - id: failing
                    type: org.flow.plugin.core.log.Log
                    message: hello
                  - id: working-directory
                    type: org.flow.plugin.core.flow.WorkingDirectory
                    tasks:
                      - id: child
                        type: org.flow.plugin.core.log.Log
                        message: hello
            """);
        harness.registerFlow(flow);
        Execution created = Executions.created(flow);
        AtomicReference<String> workingDirectoryTaskRunId = new AtomicReference<>();
        AtomicReference<WorkerTask> runningChild = new AtomicReference<>();

        // When: a sibling fails while the WorkingDirectory child is still running, so the parent interrupts both
        harness.run(created, task ->
        {
            if ("failing".equals(task.getTaskRun().getTaskId())) {
                return Results.failed(task, T0);
            }
            workingDirectoryTaskRunId.set(task.getTaskRun().getId());
            runningChild.set(childOf(task));
            return new WorkerTaskResult(runningChild.get().getTaskRun().withState(State.Type.RUNNING));
        });
        harness.run(List.of(Results.failed(runningChild.get(), T0)), ScriptedWorker.succeeding(T0));

        // Then
        assertThat(harness).hasExecutionInState(created, State.Type.FAILED);
        verify(harness.workerJobRunningStateStore()).deleteByKey(NoTransactionContext.INSTANCE, workingDirectoryTaskRunId.get());
    }

    @Test
    void shouldNotReleaseAnyLeaseWhenTheFlowHasNoWorkingDirectory() {
        // Given
        FlowWithSource flow = Flows.of(Flows.log("log"));
        harness.registerFlow(flow);
        Execution created = Executions.created(flow);

        // When
        harness.run(created, ScriptedWorker.succeeding(T0));

        // Then
        assertThat(harness).hasExecutionInState(created, State.Type.SUCCESS);
        verify(harness.workerJobRunningStateStore(), never()).deleteByKey(any(), any());
    }

    private static WorkingDirectory workingDirectory() {
        return WorkingDirectory.builder()
            .id("working-directory")
            .type(WorkingDirectory.class.getName())
            .tasks(List.of(Flows.log("child")))
            .build();
    }

    private static WorkerTask childOf(WorkerTask workingDirectory) {
        TaskRun child = workingDirectory.getTaskRun().toBuilder()
            .id(IdUtils.create())
            .taskId("child")
            .parentTaskRunId(workingDirectory.getTaskRun().getId())
            .build();
        return WorkerTask.builder().taskRun(child).build();
    }
}
