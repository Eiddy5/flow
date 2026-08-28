package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.cses.flow.core.services.executions.WorkflowUcFixture.PausedTaskRunRef;

/**
 * UC: docs/uc/flow/UC-02 用户启动、查询与取消 Flow.md
 */
class Uc02ExecutionLifecycleTest {

    @Test
    void s1UserStartsCurrentVersionAndCompletesItThroughPause() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(WorkflowUcFixture.pauseYaml(
                "uc02-s1-flow", "当前版本", true
            ));
            Execution accepted = fixture.startCreated(flow);
            Execution waiting = fixture.awaitStable(accepted);
            Execution queried = fixture.executionService().execution(
                fixture.session(), accepted.id()
            ).orElseThrow();

            assertEquals(1, fixture.executionService()
                .executions(fixture.session()).size());
            assertEquals(flow.key(), queried.flowKey());
            assertEquals(1L, queried.flowVersion());
            assertEquals(State.Type.PAUSED, waiting.state().current());

            PausedTaskRunRef pause = fixture.waitingForExecution(accepted.id());
            Execution completed = fixture.resume(
                pause, Map.of("decision", "APPROVED")
            );
            Execution reloaded = fixture.executionService().execution(
                fixture.session(), accepted.id()
            ).orElseThrow();

            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(State.Type.SUCCESS, reloaded.state().current());
            assertEquals(1L, reloaded.flowVersion());
            assertEquals(Map.of("decision", "APPROVED"),
                reloaded.requireTaskRun(pause.taskRunId()).outputs());
            assertEquals(3, reloaded.taskRuns().size());
            assertTrue(reloaded.activeTaskRuns().isEmpty());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s2OldAndNewVersionsRunIndependently() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow version1 = fixture.deploy(WorkflowUcFixture.pauseYaml(
                "uc02-s2-flow", "版本一", false
            ));
            Execution old = fixture.startAndAwait(version1);
            Flow draft2 = fixture.flowService().save(
                fixture.session(),
                PublishFlowCommand.from(version1.key(), WorkflowUcFixture.pauseYaml(
                    "uc02-s2-flow", "版本二", true
                ))
            );
            Flow version2 = fixture.flowService().save(
                fixture.session(), PublishFlowCommand.from(draft2.key(), false)
            );
            Execution fresh = fixture.startAndAwait(version2);

            fixture.restartServer();
            PausedTaskRunRef oldPause = fixture.waitingForExecution(old.id());
            PausedTaskRunRef freshPause = fixture.waitingForExecution(fresh.id());
            Execution oldCompleted = fixture.resume(
                oldPause, Map.of("decision", "OLD")
            );
            fixture.restartServer();
            Execution freshCompleted = fixture.resume(
                fixture.waitingForExecution(fresh.id()),
                Map.of("decision", "NEW")
            );

            assertEquals(1L, oldCompleted.flowVersion());
            assertEquals(2L, freshCompleted.flowVersion());
            assertEquals(State.Type.SUCCESS, oldCompleted.state().current());
            assertEquals(State.Type.SUCCESS, freshCompleted.state().current());
            assertEquals(Map.of("decision", "OLD"),
                oldCompleted.requireTaskRun(oldPause.taskRunId()).outputs());
            assertEquals(Map.of("decision", "NEW"),
                freshCompleted.requireTaskRun(freshPause.taskRunId()).outputs());
            assertNotEquals(old.id(), fresh.id());
            assertTrue(disjointTaskRunIds(oldCompleted, freshCompleted));
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s3CancelTargetsOnlyTheSelectedExecution() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(WorkflowUcFixture.pauseYaml(
                "uc02-s3-flow", "目标与对照", false
            ));
            Execution target = fixture.startAndAwait(flow);
            Execution control = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef targetPause = fixture.waitingForExecution(target.id());
            PausedTaskRunRef controlPause = fixture.waitingForExecution(control.id());

            Execution canceled = fixture.cancel(target.id());
            Execution targetReloaded = fixture.executionService().execution(
                fixture.session(), target.id()
            ).orElseThrow();
            assertEquals(State.Type.KILLED, canceled.state().current());
            assertEquals(State.Type.KILLED, targetReloaded.state().current());
            assertEquals(State.Type.KILLED,
                targetReloaded.requireTaskRun(targetPause.taskRunId())
                    .state().current());

            Execution controlCompleted = fixture.resume(
                controlPause, Map.of("decision", "APPROVED")
            );
            Execution controlReloaded = fixture.executionService().execution(
                fixture.session(), control.id()
            ).orElseThrow();
            assertEquals(State.Type.SUCCESS, controlCompleted.state().current());
            assertEquals(State.Type.SUCCESS, controlReloaded.state().current());
            assertEquals(State.Type.SUCCESS,
                controlReloaded.requireTaskRun(controlPause.taskRunId())
                    .state().current());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s4UnavailableOrInvisibleFlowsAreRejectedWithoutRuns() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow draft = fixture.flowService().save(
                fixture.session(), PublishFlowCommand.from(WorkflowUcFixture.pauseYaml(
                    "uc02-s4-draft", "未发布", false
                ))
            );
            assertRejectedStartAndNoRun(fixture, draft.key());

            Flow deleted = fixture.deploy(WorkflowUcFixture.pauseYaml(
                "uc02-s4-deleted", "已删除", false
            ));
            fixture.flowService().delete(fixture.session(), deleted.key(), false);
            assertRejectedStartAndNoRun(fixture, deleted.key());
            assertRejectedStartAndNoRun(fixture, "uc02-s4-missing");

            Session<User> other = fixture.sessionFor("other");
            Flow otherDraft = fixture.flowService().save(
                other, PublishFlowCommand.from(WorkflowUcFixture.pauseYaml(
                    "uc02-s4-other", "其他租户", false
                ))
            );
            Flow otherFlow = fixture.flowService().save(
                other, PublishFlowCommand.from(otherDraft.key(), false)
            );
            assertThrows(WorkflowException.class, () -> fixture.executionService()
                .create(fixture.session(), otherFlow.key()));
            assertTrue(fixture.executionService().executions(fixture.session())
                .isEmpty());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s5InvalidCancellationKeepsTerminalAndControlExecutionsUnchanged() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow pauseFlow = fixture.deploy(WorkflowUcFixture.pauseYaml(
                "uc02-s5-control", "合法对照", false
            ));
            Execution control = fixture.startAndAwait(pauseFlow);
            Session<User> other = fixture.sessionFor("other");
            Flow otherDraft = fixture.flowService().save(
                other, PublishFlowCommand.from(WorkflowUcFixture.pauseYaml(
                    "uc02-s5-other", "其他租户", false
                ))
            );
            Flow otherFlow = fixture.flowService().save(
                other, PublishFlowCommand.from(otherDraft.key(), false)
            );
            Execution otherExecution = fixture.startAndAwait(other, otherFlow);
            Flow logFlow = fixture.deploy("""
                key: uc02-s5-completed
                description: completed target
                tasks:
                  - key: complete
                    type: org.cses.flow.extensions.log.Log
                    message: "test step"
                """);
            Execution completed = fixture.startAndAwait(logFlow);

            assertThrows(WorkflowException.class, () -> fixture.executionService()
                .cancel(fixture.session(), "uc02-s5-missing"));
            assertThrows(WorkflowException.class, () -> fixture.executionService()
                .cancel(fixture.session(), otherExecution.id()));
            Execution completedBefore = fixture.executionService().execution(
                fixture.session(), completed.id()
            ).orElseThrow();
            assertThrows(WorkflowException.class, () -> fixture.executionService()
                .cancel(fixture.session(), completed.id()));
            assertEquals(completedBefore.state().current(),
                fixture.executionService().execution(fixture.session(), completed.id())
                    .orElseThrow().state().current());

            fixture.cancel(control.id());
            Execution canceledBefore = fixture.executionService().execution(
                fixture.session(), control.id()
            ).orElseThrow();
            assertThrows(WorkflowException.class, () -> fixture.executionService()
                .cancel(fixture.session(), control.id()));
            assertEquals(canceledBefore.state().current(),
                fixture.executionService().execution(fixture.session(), control.id())
                    .orElseThrow().state().current());

            assertEquals(State.Type.SUCCESS, completedBefore.state().current());
            assertEquals(State.Type.KILLED, canceledBefore.state().current());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s6PausedOldVersionDoesNotSwitchAfterNewVersionIsPublished() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow version1 = fixture.deploy(WorkflowUcFixture.pauseYaml(
                "uc02-s6-flow", "旧版本", false
            ));
            Execution old = fixture.startAndAwait(version1);
            Flow draft2 = fixture.flowService().save(
                fixture.session(), PublishFlowCommand.from(version1.key(),
                    WorkflowUcFixture.pauseYaml("uc02-s6-flow", "新版本", true))
            );
            Flow version2 = fixture.flowService().save(
                fixture.session(), PublishFlowCommand.from(draft2.key(), false)
            );

            fixture.restartServer();
            PausedTaskRunRef oldPause = fixture.waitingForExecution(old.id());
            Execution oldCompleted = fixture.resume(
                oldPause, Map.of("decision", "OLD")
            );
            Execution fresh = fixture.startAndAwait(version2);
            fixture.restartServer();
            Execution freshCompleted = fixture.resume(
                fixture.waitingForExecution(fresh.id()),
                Map.of("decision", "NEW")
            );

            assertEquals(1L, oldCompleted.flowVersion());
            assertEquals(2L, freshCompleted.flowVersion());
            assertEquals(2, oldCompleted.taskRuns().size());
            assertEquals(3, freshCompleted.taskRuns().size());
            assertEquals(State.Type.SUCCESS, oldCompleted.state().current());
            assertEquals(State.Type.SUCCESS, freshCompleted.state().current());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s7ResumeAndCancelRaceProducesOneConsistentTerminalResult()
        throws Exception {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open();
             var executor = Executors.newFixedThreadPool(2)) {
            Flow flow = fixture.deploy(WorkflowUcFixture.pauseYaml(
                "uc02-s7-flow", "恢复取消竞争", false
            ));
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef pause = fixture.waitingForExecution(started.id());
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);

            Future<Boolean> resumed = executor.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    fixture.resume(pause, Map.of("decision", "APPROVED"));
                    return true;
                } catch (WorkflowException failure) {
                    return false;
                }
            });
            Future<Boolean> canceled = executor.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    fixture.executionService().cancel(fixture.session(), started.id());
                    return true;
                } catch (WorkflowException failure) {
                    return false;
                }
            });
            ready.await();
            go.countDown();

            boolean resumeAccepted = resumed.get();
            boolean cancelAccepted = canceled.get();
            Execution terminal = fixture.awaitExecution(
                fixture.session(), started.id(), Execution::isTerminal
            );
            TaskRun pauseReloaded = terminal.requireTaskRun(pause.taskRunId());

            assertTrue(resumeAccepted || cancelAccepted);
            assertTrue(terminal.state().is(State.Type.SUCCESS)
                || terminal.state().is(State.Type.KILLED));
            assertEquals(terminal.state().current() == State.Type.SUCCESS
                ? State.Type.SUCCESS : State.Type.KILLED,
                pauseReloaded.state().current());
            assertTrue(terminal.activeTaskRuns().isEmpty());
            assertTrue(fixture.pausedTaskRuns().stream()
                .noneMatch(task -> task.executionId().equals(started.id())));
        }
    }

    @Test
    void s8TwoExecutionsOfOneFlowKeepIndependentCompleteHistories() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy("""
                key: uc02-s8-flow
                description: two independent executions
                tasks:
                  - key: first
                    type: org.cses.flow.extensions.log.Log
                    message: "test step"
                  - key: second
                    type: org.cses.flow.extensions.log.Log
                    message: "test step"
                  - key: third
                    type: org.cses.flow.extensions.log.Log
                    message: "test step"
                """);
            Execution first = fixture.startAndAwait(flow);
            Execution second = fixture.startAndAwait(flow);
            Execution firstQueried = fixture.executionService().execution(
                fixture.session(), first.id()).orElseThrow();
            Execution secondQueried = fixture.executionService().execution(
                fixture.session(), second.id()).orElseThrow();

            assertNotEquals(firstQueried.id(), secondQueried.id());
            assertEquals(State.Type.SUCCESS, firstQueried.state().current());
            assertEquals(State.Type.SUCCESS, secondQueried.state().current());
            assertEquals(1L, firstQueried.flowVersion());
            assertEquals(1L, secondQueried.flowVersion());
            assertEquals(3, firstQueried.taskRuns().size());
            assertEquals(3, secondQueried.taskRuns().size());
            assertTrue(disjointTaskRunIds(firstQueried, secondQueried));
            assertTrue(firstQueried.activeTaskRuns().isEmpty());
            assertTrue(secondQueried.activeTaskRuns().isEmpty());
        }
    }

    @Test
    void s9FreshServerStartsPersistedPublishedFlowInOrder() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy("""
                key: uc02-s9-flow
                description: persisted definition after restart
                tasks:
                  - key: first
                    type: org.cses.flow.extensions.log.Log
                    message: "test step"
                  - key: second
                    type: org.cses.flow.extensions.log.Log
                    message: "test step"
                  - key: third
                    type: org.cses.flow.extensions.log.Log
                    message: "test step"
                """);
            String flowKey = flow.key();
            String flowId = flow.id();
            fixture.restartServer();
            Flow persisted = fixture.flowService().latestFlow(
                fixture.session(), flowKey
            ).orElseThrow();
            Execution completed = fixture.startAndAwait(persisted);
            Execution reloaded = fixture.executionService().execution(
                fixture.session(), completed.id()).orElseThrow();

            assertEquals(flowId, persisted.id());
            assertEquals(1L, persisted.reversion());
            assertEquals(State.Type.SUCCESS, reloaded.state().current());
            assertEquals(3, reloaded.taskRuns().size());
            assertEquals(
                persisted.tasks().stream().map(task -> task.id()).toList(),
                reloaded.taskRuns().stream().map(TaskRun::taskId).toList()
            );
            assertTrue(reloaded.taskRuns().stream().allMatch(taskRun ->
                taskRun.state().current() == State.Type.SUCCESS));
            assertTrue(reloaded.activeTaskRuns().isEmpty());
        }
    }

    private static void assertRejectedStartAndNoRun(
        WorkflowUcFixture fixture,
        String flowKey
    ) {
        assertThrows(WorkflowException.class, () -> fixture.executionService()
            .create(fixture.session(), flowKey));
        assertTrue(fixture.executionService().executions(fixture.session())
            .isEmpty());
        assertTrue(fixture.pausedTaskRuns().isEmpty());
    }

    private static boolean disjointTaskRunIds(
        Execution first,
        Execution second
    ) {
        var firstIds = first.taskRuns().stream().map(TaskRun::id).toList();
        return second.taskRuns().stream().map(TaskRun::id)
            .noneMatch(firstIds::contains);
    }
}
