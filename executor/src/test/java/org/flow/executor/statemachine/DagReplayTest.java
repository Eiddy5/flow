package org.flow.executor.statemachine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.flow.core.executor.command.Replay;
import org.flow.core.executor.command.Restart;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.flows.FlowWithSource;
import org.flow.core.models.flows.State;
import org.flow.core.runners.WorkerJobEvent;
import org.flow.core.runners.WorkerTask;
import org.flow.core.runners.WorkerTaskResult;
import org.flow.core.utils.IdUtils;
import org.flow.executor.testkit.Executions;
import org.flow.executor.testkit.ExecutorTestHarness;
import org.flow.executor.testkit.Flows;
import org.flow.executor.testkit.Results;
import org.flow.executor.testkit.ScriptedWorker;
import org.flow.executor.testkit.Trace;

import static org.assertj.core.api.Assertions.assertThat;

class DagReplayTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final ExecutorTestHarness harness = ExecutorTestHarness.create();

    @Test
    void shouldRedispatchRunningSiblingAndCompleteWhenReplayingDagTask() {
        // Given
        Execution source = failedDagWithRunningSibling();

        // When: the failed task is replayed, one delivery of the Replay command
        TaskRun failed = source.findTaskRunsByTaskId("a").getFirst();
        String replayId = IdUtils.create();
        List<Trace.Emission> emitted = harness.step(Replay.from(source, replayId, failed.getId(), null, null));

        // Then: the running sibling is sent to a worker again instead of being inherited as RUNNING
        assertThat(dispatchedTaskIds(emitted)).containsExactlyInAnyOrder("a", "b");

        // And: the replay runs to the end
        harness.run(List.of(), ScriptedWorker.succeeding(T0));
        assertThat(harness.executionStateStore().findByIdWithoutAcl(replayId).getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
    }

    @Test
    void shouldRedispatchRunningSiblingAndCompleteWhenRestartingDag() {
        // Given
        Execution source = failedDagWithRunningSibling();

        // When: the execution is restarted in place, one delivery of the Restart command
        List<Trace.Emission> emitted = harness.step(Restart.from(source, null));

        // Then: the running sibling is sent to a worker again instead of being carried over as RUNNING
        Trace trace = harness.run(List.of(), ScriptedWorker.succeeding(T0));
        List<String> dispatched = new ArrayList<>(dispatchedTaskIds(emitted));
        trace.steps().forEach(step -> dispatched.addAll(dispatchedTaskIds(step.emitted())));
        assertThat(dispatched).contains("a", "b");

        // And: the restart runs to the end
        assertThat(harness.executionStateStore().findByIdWithoutAcl(source.getId()).getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
    }

    /** "a" failed while its independent sibling "b" was still running, and the execution was then terminated. */
    private Execution failedDagWithRunningSibling() {
        FlowWithSource flow = Flows.yaml("""
            id: dag-replay
            namespace: org.flow.tests
            tasks:
              - id: dag
                type: org.flow.plugin.core.flow.Dag
                tasks:
                  - task:
                      id: a
                      type: org.flow.plugin.core.log.Log
                      message: hello
                  - task:
                      id: b
                      type: org.flow.plugin.core.log.Log
                      message: hello
                  - task:
                      id: c
                      type: org.flow.plugin.core.log.Log
                      message: hello
                    dependsOn:
                      - a
                      - b
            """);
        harness.registerFlow(flow);
        Execution created = Executions.created(flow);
        harness.run(created, task ->
        {
            if ("a".equals(task.getTaskRun().getTaskId())) {
                return Results.failed(task, T0);
            }
            return new WorkerTaskResult(task.getTaskRun().withState(State.Type.RUNNING));
        });
        // the executor-error path terminates the execution without touching "b"
        Execution running = harness.executionStateStore().findByIdWithoutAcl(created.getId());
        List<TaskRun> taskRuns = running.getTaskRunList().stream()
            .map(taskRun -> "dag".equals(taskRun.getTaskId()) ? taskRun.withState(State.Type.FAILED) : taskRun)
            .toList();
        Execution source = running.withTaskRunList(taskRuns).withState(State.Type.FAILED);
        harness.executionStateStore().save(source);
        assertThat(source.findTaskRunsByTaskId("b").getFirst().getState().getCurrent()).isEqualTo(State.Type.RUNNING);
        return source;
    }

    private static List<String> dispatchedTaskIds(List<Trace.Emission> emitted) {
        return emitted.stream()
            .filter(emission -> "workerJobEvent".equals(emission.queue()))
            .map(emission -> ((WorkerTask) emission.as(WorkerJobEvent.class).job()).getTaskRun().getTaskId())
            .toList();
    }
}
