package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.externaltasks.ExternalTask;
import org.cses.flow.core.domains.externaltasks.ExternalTaskStatus;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.exceptions.WorkflowException;
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

/**
 * UC: docs/uc/flow/UC-02 用户启动、查询与取消 Flow.md
 */
class Uc02ExecutionLifecycleTest {

    @Test
    void s1StartsAndCompletesLatestDeployedFlow() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "uc02-s1-flow",
                    "初始审批 Flow",
                    true
                )
            );

            Execution started = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            String executionId = started.id();

            // PASS-S1-01
            assertNotEquals("", executionId);
            assertEquals(
                1,
                fixture.executionService().executions(fixture.session()).size()
            );
            assertEquals(flow.id(), started.flowId());
            assertEquals(1L, started.flowReversion());
            assertEquals(State.Type.PAUSED, started.state().current());

            fixture.restartServer();
            ExternalTask externalTask =
                fixture.waitingForExecution(executionId);
            // PASS-S1-02
            assertEquals(ExternalTaskStatus.WAITING, externalTask.status());
            assertEquals(executionId, externalTask.executionId());

            Execution completed =
                fixture.externalTaskService().complete(
                    fixture.session(),
                    externalTask.id(),
                    Map.of("decision", "APPROVED")
                );
            Execution reloaded = fixture.executionService().execution(
                fixture.session(),
                executionId
            ).orElseThrow();

            // PASS-S1-03
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
                ExternalTaskStatus.COMPLETED,
                fixture.externalTaskService().externalTask(
                    fixture.session(),
                    externalTask.id()
                ).orElseThrow().status()
            );
            assertEquals(
                0,
                fixture.externalTaskService().waitingTasks(
                    fixture.session()
                ).size()
            );
            assertEquals(
                flow,
                fixture.flowService().flow(
                    fixture.session(),
                    flow.id(),
                    1L
                ).orElseThrow()
            );
        }
    }

    @Test
    void s2NewStartsUseLatestReversionWithoutMovingExistingExecution() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow reversion1 = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "uc02-s2-flow",
                    "初始审批 Flow",
                    false
                )
            );
            Execution first = fixture.executionService().create(
                fixture.session(),
                reversion1.id()
            );
            String firstTaskId = reversion1.tasks().getFirst().id();

            fixture.flowService().saveDraft(
                fixture.session(),
                reversion1.id(),
                WorkflowUcFixture.pauseYaml(
                    "uc02-s2-flow",
                    "升级审批 Flow",
                    true
                )
            );
            Flow reversion2 = fixture.flowService().deploy(
                fixture.session(),
                reversion1.id()
            );
            Execution second = fixture.executionService().create(
                fixture.session(),
                reversion1.id()
            );

            Execution firstCompleted = fixture.completeAfterRestart(
                first.id(),
                Map.of("decision", "APPROVED")
            );
            Execution secondCompleted = fixture.completeAfterRestart(
                second.id(),
                Map.of("decision", "APPROVED")
            );

            // PASS-S2-01
            assertEquals(1L, firstCompleted.flowReversion());
            assertEquals(2L, secondCompleted.flowReversion());
            // PASS-S2-02
            assertEquals(State.Type.SUCCESS, firstCompleted.state().current());
            assertEquals(State.Type.SUCCESS, secondCompleted.state().current());
            // PASS-S2-03
            assertNotEquals(first.id(), second.id());
            assertTrue(firstCompleted.taskRuns().stream()
                .map(taskRun -> taskRun.id())
                .noneMatch(secondCompleted.taskRuns().stream()
                    .map(taskRun -> taskRun.id())
                    .collect(java.util.stream.Collectors.toSet())::contains));
            assertEquals(firstTaskId, reversion2.tasks().getFirst().id());
            Flow storedReversion1 = fixture.flowService().flow(
                fixture.session(),
                reversion1.id(),
                1L
            ).orElseThrow();
            assertTrue(!storedReversion1.isDeleted());
            assertEquals(
                reversion2,
                fixture.flowService().flow(
                    fixture.session(),
                    reversion2.id(),
                    2L
                ).orElseThrow()
            );
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s3CancelOnlyTargetsOneExecutionAndItsWaitingTrigger() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "uc02-s3-flow",
                    "初始审批 Flow",
                    false
                )
            );
            Execution target = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            Execution control = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            fixture.restartServer();
            ExternalTask targetExternal =
                fixture.waitingForExecution(target.id());
            ExternalTask controlExternal =
                fixture.waitingForExecution(control.id());

            Execution canceled = fixture.executionService().cancel(
                fixture.session(),
                target.id()
            );

            // PASS-S3-01
            assertEquals(State.Type.KILLED, canceled.state().current());
            assertEquals(
                State.Type.KILLED,
                canceled.taskRuns().getFirst().state().current()
            );
            assertEquals(
                ExternalTaskStatus.CANCELED,
                fixture.externalTaskService().externalTask(
                    fixture.session(),
                    targetExternal.id()
                ).orElseThrow().status()
            );
            // PASS-S3-02
            Execution controlCompleted = fixture.completeAfterRestart(
                control.id(),
                Map.of("decision", "APPROVED")
            );
            assertEquals(
                State.Type.SUCCESS,
                controlCompleted.state().current()
            );
            assertEquals(
                ExternalTaskStatus.COMPLETED,
                fixture.externalTaskService().externalTask(
                    fixture.session(),
                    controlExternal.id()
                ).orElseThrow().status()
            );
            assertEquals(
                flow,
                fixture.flowService().flow(
                    fixture.session(),
                    flow.id(),
                    1L
                ).orElseThrow()
            );
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s4RejectsDraftDeletedMissingAndCrossTenantFlows() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            FlowDraft draft = fixture.flowService().saveDraft(
                fixture.session(),
                WorkflowUcFixture.pauseYaml(
                    "uc02-s4-draft",
                    "未发布 Flow",
                    false
                )
            );
            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().create(
                    fixture.session(),
                    draft.id()
                )
            );

            Flow deployed = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "uc02-s4-deleted",
                    "已删除 Flow",
                    false
                )
            );
            fixture.flowService().delete(
                fixture.session(),
                deployed.id()
            );
            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().create(
                    fixture.session(),
                    deployed.id()
                )
            );
            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().create(
                    fixture.session(),
                    "missing-flow-id"
                )
            );

            Session<User> otherCompany =
                fixture.sessionFor("company-2");
            FlowDraft otherDraft = fixture.flowService().saveDraft(
                otherCompany,
                WorkflowUcFixture.pauseYaml(
                    "uc02-s4-other-company",
                    "其他公司 Flow",
                    false
                )
            );
            Flow otherFlow = fixture.flowService().deploy(
                otherCompany,
                otherDraft.id()
            );
            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().create(
                    fixture.session(),
                    otherFlow.id()
                )
            );

            // PASS-S4-01
            assertEquals(
                draft.raw(),
                fixture.flowService().draft(
                    fixture.session(),
                    draft.id()
                ).orElseThrow().raw()
            );
            Flow deletedFlow = fixture.flowService().flow(
                fixture.session(),
                deployed.id(),
                1L
            ).orElseThrow();
            assertTrue(deletedFlow.isDeleted());
            assertTrue(
                fixture.flowService().latestFlow(
                    fixture.session(),
                    deployed.id()
                ).isEmpty()
            );
            // PASS-S4-02
            assertTrue(
                fixture.executionService().executions(fixture.session())
                    .isEmpty()
            );
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s5RejectsMissingCrossTenantAndTerminalCancellation() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "uc02-s5-flow",
                    "取消校验 Flow",
                    false
                )
            );
            Execution running = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            long originalLockVersion = running.lockVersion();

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
            assertEquals(
                originalLockVersion,
                afterRejectedCancel.lockVersion()
            );

            Execution canceled = fixture.executionService().cancel(
                fixture.session(),
                running.id()
            );
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
            assertEquals(canceled.lockVersion(), canceledReloaded.lockVersion());

            Flow automaticFlow = fixture.deploy("""
                key: uc02-s5-completed
                tasks:
                  - key: complete
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                """);
            Execution completed = fixture.executionService().create(
                fixture.session(),
                automaticFlow.id()
            );
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

            // PASS-S5-01 and PASS-S5-02
            assertEquals(State.Type.SUCCESS, completedReloaded.state().current());
            assertEquals(
                completed.lockVersion(),
                completedReloaded.lockVersion()
            );
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s6RunningExecutionKeepsItsBoundReversionAfterDeployment() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow reversion1 = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "uc02-s6-flow",
                    "初始审批 Flow",
                    false
                )
            );
            Execution started = fixture.executionService().create(
                fixture.session(),
                reversion1.id()
            );

            fixture.flowService().saveDraft(
                fixture.session(),
                reversion1.id(),
                WorkflowUcFixture.pauseYaml(
                    "uc02-s6-flow",
                    "升级审批 Flow",
                    true
                )
            );
            fixture.flowService().deploy(
                fixture.session(),
                reversion1.id()
            );

            Execution completed = fixture.completeAfterRestart(
                started.id(),
                Map.of("decision", "APPROVED")
            );

            // PASS-S6-01
            assertEquals(1L, completed.flowReversion());
            assertEquals(started.id(), completed.id());
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(2, completed.taskRuns().size());
            assertEquals(
                reversion1.tasks().getFirst().id(),
                completed.taskRuns().getFirst().taskId()
            );

            Execution newStarted = fixture.executionService().create(
                fixture.session(),
                reversion1.id()
            );
            Execution newCompleted = fixture.completeAfterRestart(
                newStarted.id(),
                Map.of("decision", "APPROVED")
            );
            // PASS-S6-02
            assertEquals(2L, newCompleted.flowReversion());
            assertEquals(State.Type.SUCCESS, newCompleted.state().current());
            assertEquals(3, newCompleted.taskRuns().size());
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s7CompleteAndCancelRaceCommitsOneConsistentTerminalState()
        throws Exception {

        try (WorkflowUcFixture fixture = WorkflowUcFixture.open();
             var executor = Executors.newFixedThreadPool(2)) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "uc02-s7-flow",
                    "完成取消竞争",
                    false
                )
            );
            Execution started = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            fixture.restartServer();
            ExternalTask externalTask =
                fixture.waitingForExecution(started.id());
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            Future<Boolean> complete = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    fixture.externalTaskService().complete(
                        fixture.session(),
                        externalTask.id(),
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

            // PASS-S7-01
            assertEquals(1, (complete.get() ? 1 : 0) + (cancel.get() ? 1 : 0));

            Execution reloaded = fixture.executionService().execution(
                fixture.session(),
                started.id()
            ).orElseThrow();
            ExternalTask externalReloaded =
                fixture.externalTaskService().externalTask(
                    fixture.session(),
                    externalTask.id()
                ).orElseThrow();
            // PASS-S7-02
            if (reloaded.state().current() == State.Type.SUCCESS) {
                assertEquals(
                    State.Type.SUCCESS,
                    reloaded.taskRuns().getFirst().state().current()
                );
                assertEquals(
                    ExternalTaskStatus.COMPLETED,
                    externalReloaded.status()
                );
            } else {
                assertEquals(State.Type.KILLED, reloaded.state().current());
                assertEquals(
                    State.Type.KILLED,
                    reloaded.taskRuns().getFirst().state().current()
                );
                assertEquals(
                    ExternalTaskStatus.CANCELED,
                    externalReloaded.status()
                );
            }
            // PASS-S7-03
            assertEquals(2, reloaded.taskRuns().size());
            assertTrue(reloaded.taskRuns().stream()
                .allMatch(taskRun ->
                    taskRun.state().current() != State.Type.PAUSED
                        && taskRun.state().current() != State.Type.CREATED
                        && taskRun.state().current() != State.Type.RUNNING
                ));
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).stream().noneMatch(task ->
                task.executionId().equals(started.id())
            ));
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
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                  - key: second
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                  - key: third
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                """);

            Execution firstStarted = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            Execution secondStarted = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            Execution first = fixture.executionService().execution(
                fixture.session(),
                firstStarted.id()
            ).orElseThrow();
            Execution second = fixture.executionService().execution(
                fixture.session(),
                secondStarted.id()
            ).orElseThrow();

            assertNotEquals(first.id(), second.id());
            assertEquals(State.Type.SUCCESS, first.state().current());
            assertEquals(State.Type.SUCCESS, second.state().current());
            assertEquals(flow.id(), first.flowId());
            assertEquals(flow.id(), second.flowId());
            assertEquals(1L, first.flowReversion());
            assertEquals(1L, second.flowReversion());
            assertEquals(
                flow.tasks().stream().map(task -> task.id()).toList(),
                first.taskRuns().stream().map(TaskRun::taskId).toList()
            );
            assertEquals(
                flow.tasks().stream().map(task -> task.id()).toList(),
                second.taskRuns().stream().map(TaskRun::taskId).toList()
            );
            assertTrue(first.taskRuns().stream().allMatch(taskRun ->
                taskRun.state().current() == State.Type.SUCCESS
                    && taskRun.inputs().isEmpty()
                    && taskRun.outputs().isEmpty()
            ));
            assertTrue(second.taskRuns().stream().allMatch(taskRun ->
                taskRun.state().current() == State.Type.SUCCESS
                    && taskRun.inputs().isEmpty()
                    && taskRun.outputs().isEmpty()
            ));
            assertTrue(first.taskRuns().stream().map(TaskRun::id)
                .noneMatch(second.taskRuns().stream()
                    .map(TaskRun::id)
                    .collect(java.util.stream.Collectors.toSet())::contains));
            assertTrue(first.activeTaskRuns().isEmpty());
            assertTrue(second.activeTaskRuns().isEmpty());
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s9FreshServerStartsThePersistedPublishedFlowInOrder() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy("""
                key: uc02-s9-flow
                description: persisted definition after restart
                tasks:
                  - key: first
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                  - key: second
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                  - key: third
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                """);
            String flowId = flow.id();
            java.util.List<String> taskIds = flow.tasks().stream()
                .map(task -> task.id())
                .toList();

            fixture.restartServer();
            Flow persisted = fixture.flowService().latestFlow(
                fixture.session(),
                flowId
            ).orElseThrow();
            Execution completed = fixture.executionService().create(
                fixture.session(),
                persisted.id()
            );
            Execution reloaded = fixture.executionService().execution(
                fixture.session(),
                completed.id()
            ).orElseThrow();

            assertEquals(flowId, persisted.id());
            assertEquals(1L, persisted.reversion());
            assertEquals(State.Type.SUCCESS, reloaded.state().current());
            assertEquals(
                taskIds,
                reloaded.taskRuns().stream().map(TaskRun::taskId).toList()
            );
            assertEquals(3, reloaded.taskRuns().size());
            assertEquals(
                3L,
                reloaded.taskRuns().stream()
                    .map(TaskRun::id)
                    .distinct()
                    .count()
            );
            assertTrue(reloaded.taskRuns().stream().allMatch(taskRun ->
                taskRun.state().current() == State.Type.SUCCESS
            ));
            assertTrue(reloaded.activeTaskRuns().isEmpty());
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }
}
