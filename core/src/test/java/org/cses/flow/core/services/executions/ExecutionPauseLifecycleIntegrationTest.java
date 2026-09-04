package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.cses.flow.core.services.executions.WorkflowUcFixture.PausedTaskRunRef;

class ExecutionPauseLifecycleIntegrationTest {

    /**
     * Verifies that the first publication is version 2 and remains available
     * for exact lookup and Pause completion after a server restart.
     */
    @Test
    void resumesLatestDeployedFlowAfterRestart() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "pause-lifecycle-latest-flow",
                    "初始审批 Flow",
                    true
                )
            );

            Execution started = fixture.startAndAwait(flow);
            String executionId = started.id();

            assertNotEquals("", executionId);
            assertEquals(
                1,
                fixture.executionService().executions(fixture.session()).size()
            );
            assertEquals(2, flow.version());
            assertEquals(flow.key(), started.flowKey());
            assertEquals(2L, started.flowVersion());
            assertEquals(State.Type.PAUSED, started.state().current());

            fixture.restartServer();
            PausedTaskRunRef pausedTaskRun =
                fixture.waitingForExecution(executionId);
            assertEquals(
                State.Type.PAUSED,
                fixture.taskRun(pausedTaskRun).state().current()
            );
            assertEquals(executionId, pausedTaskRun.executionId());

            Execution completed = fixture.resume(
                pausedTaskRun,
                Map.of("decision", "APPROVED")
            );
            Execution reloaded = fixture.executionService().execution(
                fixture.session(),
                executionId
            ).orElseThrow();

            assertEquals(3, reloaded.taskRuns().size());
            assertEquals(
                flow.tasks().getFirst().id(),
                reloaded.taskRuns().getFirst().taskId()
            );
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(State.Type.SUCCESS, reloaded.state().current());
            assertEquals(
                java.util.List.of(
                    State.Type.SUCCESS,
                    State.Type.SUCCESS,
                    State.Type.SUCCESS
                ),
                reloaded.taskRuns().stream()
                    .map(taskRun -> taskRun.state().current())
                    .toList()
            );
            assertEquals(
                Map.of("decision", "APPROVED"),
                reloaded.requireTaskRun(pausedTaskRun.taskRunId()).outputs()
            );
            assertTrue(fixture.pausedTaskRuns().isEmpty());
            assertEquals(
                flow,
                fixture.flowService().flow(
                    fixture.session(),
                    flow.key(),
                    2L
                ).orElseThrow()
            );
        }
    }

    /**
     * Verifies that Executions bind published versions 2 and 4 across server
     * restarts while the intervening updated draft receives version 3.
     */
    @Test
    void resumedExecutionsKeepTheirBoundReversion() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow reversion2 = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "pause-lifecycle-reversion-flow",
                    "初始审批 Flow",
                    false
                )
            );
            assertEquals(2, reversion2.version());
            Execution first = fixture.startAndAwait(reversion2);
            String firstTaskId = reversion2.tasks().getFirst().id();

            Flow draft3 = fixture.flowService().save(
                fixture.session(),
                PublishFlowCommand.from(
                    reversion2.key(),
                    WorkflowUcFixture.pauseYaml(
                    "pause-lifecycle-reversion-flow",
                    "升级审批 Flow",
                    true
                    )
                )
            );
            assertEquals(3, draft3.version());
            Flow reversion4 = fixture.flowService().save(
                fixture.session(),
                PublishFlowCommand.from(reversion2.key(), false)
            );
            assertEquals(4, reversion4.version());
            Execution second = fixture.startAndAwait(reversion4);

            Execution firstCompleted = fixture.resumeAfterRestart(
                first.id(),
                Map.of("decision", "APPROVED")
            );
            Execution secondCompleted = fixture.resumeAfterRestart(
                second.id(),
                Map.of("decision", "APPROVED")
            );

            assertEquals(2L, firstCompleted.flowVersion());
            assertEquals(4L, secondCompleted.flowVersion());
            assertEquals(State.Type.SUCCESS, firstCompleted.state().current());
            assertEquals(State.Type.SUCCESS, secondCompleted.state().current());
            assertNotEquals(first.id(), second.id());
            assertTrue(firstCompleted.taskRuns().stream()
                .map(taskRun -> taskRun.id())
                .noneMatch(secondCompleted.taskRuns().stream()
                    .map(taskRun -> taskRun.id())
                    .collect(java.util.stream.Collectors.toSet())::contains));
            assertEquals(firstTaskId, reversion4.tasks().getFirst().id());
            Flow storedReversion2 = fixture.flowService().flow(
                fixture.session(),
                reversion2.key(),
                2L
            ).orElseThrow();
            assertFalse(storedReversion2.deleted());
            assertEquals(
                reversion4,
                fixture.flowService().flow(
                    fixture.session(),
                    reversion4.key(),
                    4L
                ).orElseThrow()
            );
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    /**
     * Verifies that canceling one of two version-2 Executions leaves the
     * control Execution resumable and the published version queryable.
     */
    @Test
    void cancelOnlyTargetsOnePausedExecution() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "pause-lifecycle-cancel-target-flow",
                    "初始审批 Flow",
                    false
                )
            );
            assertEquals(2, flow.version());
            Execution target = fixture.startAndAwait(flow);
            Execution control = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef targetPause =
                fixture.waitingForExecution(target.id());
            PausedTaskRunRef controlPause =
                fixture.waitingForExecution(control.id());

            Execution canceled = fixture.cancel(target.id());

            assertEquals(State.Type.KILLED, canceled.state().current());
            assertEquals(
                State.Type.KILLED,
                canceled.taskRuns().getFirst().state().current()
            );
            assertEquals(
                State.Type.KILLED,
                fixture.taskRun(targetPause).state().current()
            );
            Execution controlCompleted = fixture.resumeAfterRestart(
                control.id(),
                Map.of("decision", "APPROVED")
            );
            assertEquals(
                State.Type.SUCCESS,
                controlCompleted.state().current()
            );
            assertEquals(
                State.Type.SUCCESS,
                fixture.taskRun(controlPause).state().current()
            );
            assertEquals(
                flow,
                fixture.flowService().flow(
                    fixture.session(),
                    flow.key(),
                    2L
                ).orElseThrow()
            );
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    /**
     * Verifies unavailable Flow start rejection and confirms that deletion of
     * a first publication appends a distinct version-3 database row.
     */
    @Test
    void createRejectsDraftDeletedMissingAndCrossTenantFlows() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow draft = fixture.flowService().save(
                fixture.session(),
                PublishFlowCommand.from(WorkflowUcFixture.pauseYaml(
                    "pause-lifecycle-draft",
                    "未发布 Flow",
                    false
                ))
            );
            assertEquals(1, draft.version());
            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().create(
                    fixture.session(),
                    draft.key(),
                    Optional.empty(),
                    Map.of()
                )
            );

            Flow deployed = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "pause-lifecycle-deleted",
                    "已删除 Flow",
                    false
                )
            );
            assertEquals(2, deployed.version());
            Flow deletion = fixture.flowService().delete(
                fixture.session(),
                deployed.key(),
                false
            );
            assertNotEquals(deployed.id(), deletion.id());
            assertEquals(3, deletion.version());
            assertTrue(deletion.deleted());
            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().create(
                    fixture.session(),
                    deployed.key(),
                    Optional.empty(),
                    Map.of()
                )
            );
            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().create(
                    fixture.session(),
                    "missing-flow-key",
                    Optional.empty(),
                    Map.of()
                )
            );

            Session<User> otherCompany =
                fixture.sessionFor("company-2");
            Flow otherDraft = fixture.flowService().save(
                otherCompany,
                PublishFlowCommand.from(WorkflowUcFixture.pauseYaml(
                    "pause-lifecycle-other-company",
                    "其他公司 Flow",
                    false
                ))
            );
            Flow otherFlow = fixture.flowService().save(
                otherCompany,
                PublishFlowCommand.from(otherDraft.key(), false)
            );
            assertEquals(1, otherDraft.version());
            assertEquals(2, otherFlow.version());
            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().create(
                    fixture.session(),
                    otherFlow.key(),
                    Optional.empty(),
                    Map.of()
                )
            );

            assertEquals(
                draft.source(),
                fixture.flowService().draft(
                    fixture.session(),
                    draft.key()
                ).orElseThrow().source()
            );
            Flow deletedFlow = fixture.flowService().flow(
                fixture.session(),
                deployed.key(),
                3L
            ).orElseThrow();
            assertEquals(deletion.id(), deletedFlow.id());
            assertEquals(3, deletedFlow.version());
            assertTrue(deletedFlow.deleted());
            assertTrue(
                fixture.flowService().latestFlow(
                    fixture.session(),
                    deployed.key()
                ).isEmpty()
            );
            assertTrue(
                fixture.executionService().executions(fixture.session())
                    .isEmpty()
            );
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void cancelRejectsMissingCrossTenantAndTerminalExecutions() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "pause-lifecycle-cancel-validation-flow",
                    "取消校验 Flow",
                    false
                )
            );
            Execution running = fixture.startAndAwait(flow);

            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().cancel(
                    fixture.session(),
                    "missing-execution-id"
                )
            );
            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().cancel(
                    fixture.sessionFor("company-2"),
                    running.id()
                )
            );
            Execution afterRejectedCancel =
                fixture.executionService().execution(
                    fixture.session(),
                    running.id()
            ).orElseThrow();
            assertEquals(State.Type.PAUSED, afterRejectedCancel.state().current());

            fixture.cancel(running.id());
            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().cancel(
                    fixture.session(),
                    running.id()
                )
            );
            Execution canceledReloaded =
                fixture.executionService().execution(
                    fixture.session(),
                    running.id()
            ).orElseThrow();
            assertEquals(State.Type.KILLED, canceledReloaded.state().current());

            Flow logFlow = fixture.deploy("""
                key: pause-lifecycle-completed
                tasks:
                  - key: complete
                    type: org.cses.flow.extensions.log.Log
                    message: "test step"
                """);
            Execution completed = fixture.startAndAwait(logFlow);
            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().cancel(
                    fixture.session(),
                    completed.id()
                )
            );
            Execution completedReloaded =
                fixture.executionService().execution(
                    fixture.session(),
                    completed.id()
                ).orElseThrow();

            assertEquals(State.Type.SUCCESS, completedReloaded.state().current());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    /**
     * Verifies that a paused Execution stays bound to published version 2
     * after an updated draft and publication append versions 3 and 4.
     */
    @Test
    void pausedExecutionKeepsItsBoundReversionAfterDeployment() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow reversion2 = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "pause-lifecycle-bound-reversion-flow",
                    "初始审批 Flow",
                    false
                )
            );
            assertEquals(2, reversion2.version());
            Execution started = fixture.startAndAwait(reversion2);

            Flow draft3 = fixture.flowService().save(
                fixture.session(),
                PublishFlowCommand.from(
                    reversion2.key(),
                    WorkflowUcFixture.pauseYaml(
                    "pause-lifecycle-bound-reversion-flow",
                    "升级审批 Flow",
                    true
                    )
                )
            );
            assertEquals(3, draft3.version());
            Flow reversion4 = fixture.flowService().save(
                fixture.session(),
                PublishFlowCommand.from(reversion2.key(), false)
            );
            assertEquals(4, reversion4.version());

            Execution completed = fixture.resumeAfterRestart(
                started.id(),
                Map.of("decision", "APPROVED")
            );

            assertEquals(2L, completed.flowVersion());
            assertEquals(started.id(), completed.id());
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(2, completed.taskRuns().size());
            assertEquals(
                reversion2.tasks().getFirst().id(),
                completed.taskRuns().getFirst().taskId()
            );

            Execution newStarted = fixture.startAndAwait(reversion4);
            Execution newCompleted = fixture.resumeAfterRestart(
                newStarted.id(),
                Map.of("decision", "APPROVED")
            );
            assertEquals(4L, newCompleted.flowVersion());
            assertEquals(State.Type.SUCCESS, newCompleted.state().current());
            assertEquals(3, newCompleted.taskRuns().size());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void resumeAndCancelRaceCommitsOneConsistentTerminalState()
        throws Exception {

        try (WorkflowUcFixture fixture = WorkflowUcFixture.open();
             var executor = Executors.newFixedThreadPool(2)) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "pause-lifecycle-resume-cancel-race-flow",
                    "完成取消竞争",
                    false
                )
            );
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef pausedTaskRun =
                fixture.waitingForExecution(started.id());
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            Future<Boolean> complete = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    fixture.resume(
                        pausedTaskRun,
                        Map.of("decision", "approved")
                    );
                    return true;
                } catch (WorkflowException failure) {
                    return false;
                }
            });
            Future<Boolean> cancel = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    fixture.executionService().cancel(
                        fixture.session(),
                        started.id()
                    );
                    return true;
                } catch (WorkflowException failure) {
                    return false;
                }
            });
            ready.await();
            start.countDown();

            boolean resumeAccepted = complete.get();
            boolean cancelCompleted = cancel.get();
            assertTrue(resumeAccepted || cancelCompleted);

            Execution reloaded = fixture.awaitExecution(
                fixture.session(),
                started.id(),
                Execution::isTerminal
            );
            TaskRun pauseReloaded = reloaded.requireTaskRun(
                pausedTaskRun.taskRunId()
            );
            if (reloaded.state().current() == State.Type.SUCCESS) {
                assertEquals(
                    State.Type.SUCCESS,
                    reloaded.taskRuns().getFirst().state().current()
                );
                assertEquals(
                    State.Type.SUCCESS,
                    pauseReloaded.state().current()
                );
            } else {
                assertEquals(State.Type.KILLED, reloaded.state().current());
                assertEquals(
                    State.Type.KILLED,
                    reloaded.taskRuns().getFirst().state().current()
                );
                assertEquals(
                    State.Type.KILLED,
                    pauseReloaded.state().current()
                );
            }
            assertEquals(2, reloaded.taskRuns().size());
            assertTrue(reloaded.taskRuns().stream()
                .allMatch(taskRun ->
                    taskRun.state().current() != State.Type.PAUSED
                        && taskRun.state().current() != State.Type.CREATED
                        && taskRun.state().current() != State.Type.RUNNING
                ));
            assertTrue(fixture.pausedTaskRuns().stream().noneMatch(task ->
                task.executionId().equals(started.id())
            ));
        }
    }

}
