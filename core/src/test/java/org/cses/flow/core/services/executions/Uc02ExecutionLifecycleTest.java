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

/**
 * UC: docs/uc/flow/UC-02 用户启动、查询与取消 Flow.md
 */
class Uc02ExecutionLifecycleTest {

    /**
     * Verifies that the initial draft is published as version 2 and that its
     * Execution remains bound to that version through Pause completion.
     */
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
            assertEquals(2, flow.version());
            assertEquals(flow.key(), queried.flowKey());
            assertEquals(2L, queried.flowVersion());
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
            assertEquals(2L, reloaded.flowVersion());
            assertEquals(Map.of("decision", "APPROVED"),
                reloaded.requireTaskRun(pause.taskRunId()).outputs());
            assertEquals(3, reloaded.taskRuns().size());
            assertTrue(reloaded.activeTaskRuns().isEmpty());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    /**
     * Verifies the shared v1 draft, v2 publication, v3 draft, and v4
     * publication sequence while old and new Executions stay isolated.
     */
    @Test
    void s2OldAndNewVersionsRunIndependently() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow version2 = fixture.deploy(WorkflowUcFixture.pauseYaml(
                "uc02-s2-flow", "版本一", false
            ));
            assertEquals(2, version2.version());
            Execution old = fixture.startAndAwait(version2);
            Flow draft3 = fixture.flowService().save(
                fixture.session(),
                PublishFlowCommand.from(version2.key(), WorkflowUcFixture.pauseYaml(
                    "uc02-s2-flow", "版本二", true
                ))
            );
            assertEquals(3, draft3.version());
            Flow version4 = fixture.flowService().save(
                fixture.session(), PublishFlowCommand.from(draft3.key(), false)
            );
            assertEquals(4, version4.version());
            Execution fresh = fixture.startAndAwait(version4);

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

            assertEquals(2L, oldCompleted.flowVersion());
            assertEquals(4L, freshCompleted.flowVersion());
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

    /**
     * Verifies that draft-only, deleted, missing, and cross-tenant Flows
     * cannot start, and that deployed deletion appends version 3.
     */
    @Test
    void s4UnavailableOrInvisibleFlowsAreRejectedWithoutRuns() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow draft = fixture.flowService().save(
                fixture.session(), PublishFlowCommand.from(WorkflowUcFixture.pauseYaml(
                    "uc02-s4-draft", "未发布", false
                ))
            );
            assertEquals(1, draft.version());
            assertRejectedStartAndNoRun(fixture, draft.key());

            Flow deployed = fixture.deploy(WorkflowUcFixture.pauseYaml(
                "uc02-s4-deleted", "已删除", false
            ));
            assertEquals(2, deployed.version());
            Flow deleted = fixture.flowService().delete(
                fixture.session(),
                deployed.key(),
                false
            );
            assertNotEquals(deployed.id(), deleted.id());
            assertEquals(3, deleted.version());
            assertTrue(deleted.deleted());
            Flow storedPublished = fixture.flowService().flow(
                fixture.session(),
                deployed.key(),
                2L
            ).orElseThrow();
            assertFalse(storedPublished.deleted());
            Flow storedDeletion = fixture.flowService().flow(
                fixture.session(),
                deployed.key(),
                3L
            ).orElseThrow();
            assertEquals(deleted.id(), storedDeletion.id());
            assertTrue(storedDeletion.deleted());
            assertRejectedStartAndNoRun(fixture, deployed.key());
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
            assertEquals(1, otherDraft.version());
            assertEquals(2, otherFlow.version());
            assertThrows(WorkflowException.class, () -> fixture.executionService()
                .create(
                    fixture.session(),
                    otherFlow.key(),
                    Optional.empty(),
                    Map.of()
                ));
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

    /**
     * Verifies that an Execution bound to published version 2 does not switch
     * after the next draft and publication append versions 3 and 4.
     */
    @Test
    void s6PausedOldVersionDoesNotSwitchAfterNewVersionIsPublished() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow version2 = fixture.deploy(WorkflowUcFixture.pauseYaml(
                "uc02-s6-flow", "旧版本", false
            ));
            assertEquals(2, version2.version());
            Execution old = fixture.startAndAwait(version2);
            Flow draft3 = fixture.flowService().save(
                fixture.session(), PublishFlowCommand.from(version2.key(),
                    WorkflowUcFixture.pauseYaml("uc02-s6-flow", "新版本", true))
            );
            assertEquals(3, draft3.version());
            Flow version4 = fixture.flowService().save(
                fixture.session(), PublishFlowCommand.from(draft3.key(), false)
            );
            assertEquals(4, version4.version());

            fixture.restartServer();
            PausedTaskRunRef oldPause = fixture.waitingForExecution(old.id());
            Execution oldCompleted = fixture.resume(
                oldPause, Map.of("decision", "OLD")
            );
            Execution fresh = fixture.startAndAwait(version4);
            fixture.restartServer();
            Execution freshCompleted = fixture.resume(
                fixture.waitingForExecution(fresh.id()),
                Map.of("decision", "NEW")
            );

            assertEquals(2L, oldCompleted.flowVersion());
            assertEquals(4L, freshCompleted.flowVersion());
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

    /**
     * Verifies that two independent Executions both bind the current
     * publication at version 2 while retaining disjoint histories.
     */
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

            assertEquals(2, flow.version());
            assertNotEquals(firstQueried.id(), secondQueried.id());
            assertEquals(State.Type.SUCCESS, firstQueried.state().current());
            assertEquals(State.Type.SUCCESS, secondQueried.state().current());
            assertEquals(2L, firstQueried.flowVersion());
            assertEquals(2L, secondQueried.flowVersion());
            assertEquals(3, firstQueried.taskRuns().size());
            assertEquals(3, secondQueried.taskRuns().size());
            assertTrue(disjointTaskRunIds(firstQueried, secondQueried));
            assertTrue(firstQueried.activeTaskRuns().isEmpty());
            assertTrue(secondQueried.activeTaskRuns().isEmpty());
        }
    }

    /**
     * Verifies that publication version 2 survives a server restart and is
     * used for the next Execution without skipped or duplicated Tasks.
     */
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
            assertEquals(2, persisted.version());
            assertEquals(2L, persisted.reversion());
            assertEquals(2L, reloaded.flowVersion());
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

    /**
     * Starts explicit historical version 2 after version 4 becomes current,
     * then verifies the completed Execution exposes only version 2 history
     * and Resume output through public service queries.
     */
    @Test
    void s10UserExplicitlyStartsHistoricalPublishedVersion() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow version2 = fixture.deploy(WorkflowUcFixture.pauseYaml(
                "uc02-s10-flow", "historical version two", true
            ));
            Flow draft3 = fixture.flowService().save(
                fixture.session(),
                PublishFlowCommand.from(version2.key(), """
                    key: uc02-s10-flow
                    description: current version four
                    tasks:
                      - key: version-four-only
                        type: org.cses.flow.extensions.log.Log
                        message: "version 4 only"
                    """)
            );
            Flow version4 = fixture.flowService().save(
                fixture.session(),
                PublishFlowCommand.from(draft3.key(), false)
            );
            Flow historical = fixture.flowService().flow(
                fixture.session(), version2.key(), 2L
            ).orElseThrow();
            Flow current = fixture.flowService().latestFlow(
                fixture.session(), version2.key()
            ).orElseThrow();

            assertEquals(2, version2.version());
            assertEquals(3, draft3.version());
            assertEquals(4, version4.version());
            assertEquals(2, historical.version());
            assertEquals(4, current.version());
            assertEquals("historical version two", historical.description());
            assertEquals("current version four", current.description());

            var accepted = fixture.executionService().create(
                fixture.session(),
                historical.key(),
                Optional.of(2L),
                Map.of()
            );
            Execution waiting = fixture.awaitStable(
                accepted.getExecutionId()
            );
            PausedTaskRunRef pause = fixture.waitingForExecution(
                accepted.getExecutionId()
            );
            Execution completed = fixture.resume(
                pause, Map.of("decision", "V2-HISTORY")
            );
            Execution queried = fixture.executionService().execution(
                fixture.session(), accepted.getExecutionId()
            ).orElseThrow();
            var historicalTaskIds = historical.allTasks().stream()
                .map(task -> task.id())
                .toList();
            var currentTaskIds = current.allTasks().stream()
                .map(task -> task.id())
                .toList();
            var executedTaskIds = queried.taskRuns().stream()
                .map(TaskRun::taskId)
                .toList();

            assertEquals(1, fixture.executionService()
                .executions(fixture.session()).size());
            assertEquals(State.Type.PAUSED, waiting.state().current());
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(State.Type.SUCCESS, queried.state().current());
            assertEquals(2L, queried.flowVersion());
            assertEquals(historicalTaskIds, executedTaskIds);
            assertTrue(executedTaskIds.stream()
                .noneMatch(currentTaskIds::contains));
            assertEquals(Map.of("decision", "V2-HISTORY"),
                queried.requireTaskRun(pause.taskRunId()).outputs());
            assertTrue(queried.activeTaskRuns().isEmpty());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    private static void assertRejectedStartAndNoRun(
        WorkflowUcFixture fixture,
        String key
    ) {
        assertThrows(WorkflowException.class, () -> fixture.executionService()
            .create(
                fixture.session(),
                key,
                Optional.empty(),
                Map.of()
            ));
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
