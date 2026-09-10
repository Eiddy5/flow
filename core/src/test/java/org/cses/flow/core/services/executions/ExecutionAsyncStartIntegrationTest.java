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
                    onPause:
                      key: create-confirmation
                      type: org.cses.flow.extensions.log.Log
                      message: "创建确认步骤"
                    onResume:
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
                    onPause:
                      key: target-block
                      type: org.cses.flow.core.services.executions.TestWorkflowTask
                    onResume:
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
    /** 相同退回命令在真实消费入口重投不新增实例，旧 Resume 回调不能推进父或子实例。 */
    @Test
    void replayRedeliveryAndOldCallbacksKeepExactlyOneDerivedExecution() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy("""
                key: replay-command-idempotency
                tasks:
                  - key: target
                    type: org.cses.flow.extensions.log.Log
                    message: preparation
                  - key: approval
                    type: org.cses.flow.extensions.flow.Pause
                    onPause:
                      key: prepare-approval
                      type: org.cses.flow.extensions.log.Log
                      message: waiting
                    onResume:
                      - key: decision
                        type: STRING
                  - key: record
                    type: org.cses.flow.extensions.log.Log
                    message: '{{ outputs.approval.decision }}'
                """);
            Execution source = fixture.startAndAwait(flow);
            var oldPause = fixture.waitingForExecution(source.id());
            var command = org.cses.flow.executor.commands.Rewind.from(fixture.session(), source.id(),
                oldPause.taskRunId(), source.taskRuns().getFirst().id(), "durable correction");
            var oldCallback = org.cses.flow.executor.commands.Resume.from(fixture.session(), source.id(),
                oldPause.taskRunId(), Map.of("decision", "STALE"));
            fixture.deliverCommand(command);
            Execution derived = fixture.awaitStable(command.getReplayExecutionId());
            Execution stopped = fixture.executionService().execution(fixture.session(), source.id()).orElseThrow();
            assertEquals(State.Type.KILLED, stopped.state().current());
            var retainedIds = derived.taskRuns().stream().map(TaskRun::id).toList();
            fixture.restartServer();
            fixture.deliverCommand(command);
            fixture.deliverCommand(oldCallback);
            Execution reloaded = fixture.awaitStable(derived.id());
            assertEquals(retainedIds, reloaded.taskRuns().stream().map(TaskRun::id).toList());
            assertEquals(derived.taskRuns().stream().map(TaskRun::outputs).toList(),
                reloaded.taskRuns().stream().map(TaskRun::outputs).toList());
            assertEquals(2, fixture.executionService().lineage(fixture.session(), derived.id()).size());
            assertThrows(WorkflowException.class,
                () -> fixture.executionService().lineage(fixture.sessionFor("other"), derived.id()));
            Execution completed = fixture.resume(fixture.waitingForExecution(derived.id()), Map.of("decision", "NEW"));
            assertEquals(State.Type.SUCCESS, completed.state().current());
            fixture.restartServer();
            fixture.deliverCommand(command);
            fixture.deliverCommand(oldCallback);
            var members = fixture.executionService().lineage(fixture.session(), source.id());
            assertEquals(2, members.size());
            assertTrue(members.stream().allMatch(Execution::isTerminal));
            Execution old = fixture.executionService().execution(fixture.session(), source.id()).orElseThrow();
            assertEquals(stopped.state(), old.state());
            assertEquals(stopped.taskRuns().stream().map(TaskRun::state).toList(), old.taskRuns().stream().map(TaskRun::state).toList());
            assertEquals(Map.of(), old.requireTaskRun(oldPause.taskRunId()).outputs());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    /** 等待并行 Worker 真实提交结果后才交接，重投前不创建派生快照或终止源实例。 */
    @Test
    void rewindWaitsForInFlightWorkerBeforeSnapshotHandoff() throws InterruptedException {
        TestWorkflowTask.blockNextRun();
        try (WorkflowUcFixture fixture = WorkflowUcFixture.openWithProperties(TEST_TASK)) {
            try {
                Flow flow = fixture.deploy("""
                    key: replay-in-flight-worker
                    tasks:
                      - key: parallel
                        type: org.cses.flow.extensions.flow.Parallel
                        tasks:
                          - key: approval-branch
                            type: org.cses.flow.extensions.flow.Sequence
                            tasks:
                              - key: target
                                type: org.cses.flow.extensions.log.Log
                                message: preparation
                              - key: approval
                                type: org.cses.flow.extensions.flow.Pause
                                onPause:
                                  key: prepare-approval
                                  type: org.cses.flow.extensions.log.Log
                                  message: waiting
                                onResume:
                                  - key: decision
                                    type: STRING
                          - key: worker-branch
                            type: org.cses.flow.extensions.flow.Sequence
                            tasks:
                              - key: worker-gate
                                type: org.cses.flow.extensions.flow.Pause
                                onPause:
                                  key: prepare-worker-gate
                                  type: org.cses.flow.extensions.log.Log
                                  message: gate
                                onResume:
                                  - key: release
                                    type: STRING
                              - key: target-block
                                type: org.cses.flow.core.services.executions.TestWorkflowTask
                    """);
                Execution accepted = fixture.startAndAwait(flow);
                var gate = fixture.waitingForOutput(accepted.id(), "release");
                fixture.executionService().resume(fixture.session(), gate.executionId(), gate.taskRunId(), Map.of("release", "GO"));
                assertTrue(TestWorkflowTask.awaitBlockingRun());
                String pauseTaskId = flow.allTasks().stream().filter(task -> task.key().equals("approval"))
                    .findFirst().orElseThrow().id();
                String targetTaskId = flow.allTasks().stream().filter(task -> task.key().equals("target"))
                    .findFirst().orElseThrow().id();
                String workerTaskId = flow.allTasks().stream().filter(task -> task.key().equals("target-block"))
                    .findFirst().orElseThrow().id();
                Execution running = fixture.awaitExecution(fixture.session(), accepted.id(), execution ->
                    execution.taskRunsForTask(pauseTaskId).stream().anyMatch(run -> run.state().is(State.Type.PAUSED)));
                TaskRun worker = running.taskRunsForTask(workerTaskId).getFirst();
                assertEquals(State.Type.RUNNING, worker.state().current());
                var command = org.cses.flow.executor.commands.Rewind.from(fixture.session(), running.id(),
                    running.taskRunsForTask(pauseTaskId).getFirst().id(),
                    running.taskRunsForTask(targetTaskId).getFirst().id(), "wait for worker result");
                WorkflowException failure = assertThrows(WorkflowException.class, () -> fixture.deliverCommand(command));
                assertTrue(failure.getMessage().contains("in-flight Worker"));
                assertTrue(fixture.executionService().execution(fixture.session(), command.getReplayExecutionId()).isEmpty());
                Execution unchanged = fixture.executionService().execution(fixture.session(), running.id()).orElseThrow();
                assertEquals(running.state(), unchanged.state());
                assertEquals(running.taskRuns().stream().map(TaskRun::state).toList(),
                    unchanged.taskRuns().stream().map(TaskRun::state).toList());
                assertEquals(1, fixture.executionService().lineage(fixture.session(), running.id()).size());

                TestWorkflowTask.releaseBlockingRun();
                Execution resultSaved = fixture.awaitStable(running.id());
                assertEquals(State.Type.SUCCESS, resultSaved.requireTaskRun(worker.id()).state().current());
                fixture.deliverCommand(command);
                Execution derived = fixture.awaitStable(command.getReplayExecutionId());
                assertEquals(State.Type.KILLED, fixture.executionService().execution(fixture.session(), running.id())
                    .orElseThrow().state().current());
                assertEquals(resultSaved.requireTaskRun(worker.id()).state(), derived.requireTaskRun(worker.id()).state());
                assertEquals(resultSaved.requireTaskRun(worker.id()).outputs(), derived.requireTaskRun(worker.id()).outputs());
                assertTrue(derived.inheritedTaskRuns().stream().anyMatch(run -> run.id().equals(worker.id())));
                assertTrue(derived.ownTaskRuns().stream().noneMatch(run -> run.taskId().equals(workerTaskId)));
                assertEquals(1, TestWorkflowTask.blockingRunCount());
                fixture.restartServer();
                Execution completed = fixture.resume(fixture.waitingForOutput(derived.id(), "decision"), Map.of("decision", "APPROVED"));
                assertEquals(State.Type.SUCCESS, completed.state().current());
                assertEquals(1, TestWorkflowTask.blockingRunCount());
                assertTrue(fixture.executionService().lineage(fixture.session(), completed.id()).stream().allMatch(Execution::isTerminal));
                assertTrue(fixture.pausedTaskRuns().isEmpty());
            } finally {
                TestWorkflowTask.releaseBlockingRun();
            }
        } finally {
            TestWorkflowTask.releaseBlockingRun();
        }
    }
}
