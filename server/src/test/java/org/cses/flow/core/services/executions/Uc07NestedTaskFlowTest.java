package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.externaltasks.ExternalTask;
import org.cses.flow.core.domains.externaltasks.ExternalTaskStatus;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC: docs/uc/flow/UC-07 用户处理多阶段外派流程.md
 */
class Uc07NestedTaskFlowTest {

    private static final List<String> INITIAL_RUN_ORDER = List.of(
        "receive-request",
        "prepare-release",
        "backend-build",
        "frontend-build",
        "backend-review",
        "frontend-review"
    );

    private static final List<String> COMPLETE_RUN_ORDER = List.of(
        "receive-request",
        "prepare-release",
        "backend-build",
        "frontend-build",
        "backend-review",
        "frontend-review",
        "integrate-results",
        "security-stage",
        "security-check",
        "security-approve",
        "publish-artifact",
        "notify-result"
    );

    @Test
    void s1CompletesTwoLevelNestedFlowThroughPublicQueries() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                baselineYaml("uc07-s1-flow")
            );
            Map<String, Task> tasks = tasksByKey(flow);

            assertEquals(12, tasks.size());
            assertEquals(12, new HashSet<>(tasks.keySet()).size());
            Set<String> taskIds = new HashSet<>();
            tasks.values().forEach(task -> {
                assertNotNull(task.id());
                assertFalse(task.id().isBlank());
                assertTrue(taskIds.add(task.id()));
            });
            assertDefinitionTopology(tasks);

            Execution execution = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );

            // PASS-S1-01
            assertEquals(
                1,
                fixture.executionService().executions(fixture.session()).size()
            );
            assertEquals(flow.id(), execution.flowId());
            assertEquals(flow.reversion(), execution.flowReversion());
            assertEquals(State.Type.WAITING, execution.state().current());
            assertEquals(INITIAL_RUN_ORDER, taskKeys(execution, flow));
            assertEquals(6, execution.taskRuns().size());

            TaskRun backendReview = run(execution, tasks.get("backend-review"));
            TaskRun frontendReview = run(
                execution,
                tasks.get("frontend-review")
            );
            fixture.restartServer();
            ExternalTask backendExternal = fixture.waitingForOutput(
                execution.id(),
                "backendResult"
            );
            ExternalTask frontendExternal = fixture.waitingForOutput(
                execution.id(),
                "frontendResult"
            );

            // PASS-S1-02
            assertCompleted(execution, tasks, "receive-request");
            assertCompleted(execution, tasks, "prepare-release");
            assertCompleted(execution, tasks, "backend-build");
            assertCompleted(execution, tasks, "frontend-build");
            assertEquals(State.Type.WAITING, backendReview.state().current());
            assertEquals(State.Type.WAITING, frontendReview.state().current());
            assertEquals(ExternalTaskStatus.WAITING, backendExternal.status());
            assertEquals(ExternalTaskStatus.WAITING, frontendExternal.status());
            assertNotEquals(backendExternal.id(), frontendExternal.id());
            assertEquals(execution.id(), backendExternal.executionId());
            assertEquals(execution.id(), frontendExternal.executionId());
            assertEquals(backendReview.id(), backendExternal.taskRunId());
            assertEquals(frontendReview.id(), frontendExternal.taskRunId());

            assertRuntimeTopology(execution, tasks);
            assertNoRunsFrom(execution, flow, "integrate-results");

            Execution afterBackend =
                fixture.externalTaskService().complete(
                    fixture.session(),
                    backendExternal.id(),
                    Map.of("backendResult", "PASS")
                );
            // PASS-S1-03
            assertNoRun(afterBackend, tasks.get("integrate-results"));

            fixture.restartServer();
            frontendExternal = fixture.waitingForOutput(
                execution.id(),
                "frontendResult"
            );
            Execution atSecurity =
                fixture.externalTaskService().complete(
                    fixture.session(),
                    frontendExternal.id(),
                    Map.of("frontendResult", "PASS")
                );
            // PASS-S1-04
            assertIntegration(atSecurity, tasks);

            fixture.restartServer();
            // PASS-S1-05
            ExternalTask securityExternal = fixture.waitingForOutput(
                execution.id(),
                "securityDecision"
            );
            Execution completed =
                fixture.externalTaskService().complete(
                    fixture.session(),
                    securityExternal.id(),
                    Map.of("securityDecision", "APPROVED")
                );
            // PASS-S1-06
            assertEquals(State.Type.COMPLETED, completed.state().current());
            assertEquals(12, completed.taskRuns().size());
            assertTrue(completed.taskRuns().stream()
                .allMatch(run ->
                    run.state().current() == State.Type.COMPLETED
                ));
            assertEquals(COMPLETE_RUN_ORDER, taskKeys(completed, flow));
            tasks.values().forEach(task ->
                assertEquals(1, countRuns(completed, task))
            );
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s2CompletesLongFlowAcrossThreeDeepWaitingPoints() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                baselineYaml("uc07-s2-flow")
            );
            Map<String, Task> tasks = tasksByKey(flow);
            Execution started = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            // PASS-S2-02：关闭启动 server 后，后续每个 id 都来自当前
            // server 的公开等待任务查询。
            fixture.restartServer();
            ExternalTask backendExternal = fixture.waitingForOutput(
                started.id(),
                "backendResult"
            );
            ExternalTask frontendExternal = fixture.waitingForOutput(
                started.id(),
                "frontendResult"
            );

            Execution afterBackend = fixture.externalTaskService().complete(
                fixture.session(),
                backendExternal.id(),
                Map.of("backendResult", "PASS")
            );

            // PASS-S2-01
            assertEquals(
                ExternalTaskStatus.COMPLETED,
                externalTask(fixture, backendExternal.id()).status()
            );
            assertEquals(
                Map.of("backendResult", "PASS"),
                run(afterBackend, tasks.get("backend-review")).outputs()
            );
            assertEquals(
                State.Type.WAITING,
                run(afterBackend, tasks.get("frontend-review")).state().current()
            );
            assertEquals(
                ExternalTaskStatus.WAITING,
                externalTask(fixture, frontendExternal.id()).status()
            );
            assertNoRun(afterBackend, tasks.get("integrate-results"));

            fixture.restartServer();
            frontendExternal = fixture.waitingForOutput(
                started.id(),
                "frontendResult"
            );
            Execution afterFrontend = fixture.externalTaskService().complete(
                fixture.session(),
                frontendExternal.id(),
                Map.of("frontendResult", "PASS")
            );
            TaskRun integration = run(
                afterFrontend,
                tasks.get("integrate-results")
            );

            assertEquals(1, countRuns(
                afterFrontend,
                tasks.get("integrate-results")
            ));
            assertEquals(State.Type.COMPLETED, integration.state().current());
            assertEquals(
                Map.of(
                    "dependOnOutputs",
                    Map.of(
                        "backend-review",
                        Map.of("backendResult", "PASS"),
                        "frontend-review",
                        Map.of("frontendResult", "PASS")
                    )
                ),
                integration.inputs()
            );

            TaskRun securityCheck = run(
                afterFrontend,
                tasks.get("security-check")
            );
            TaskRun securityApprove = run(
                afterFrontend,
                tasks.get("security-approve")
            );
            fixture.restartServer();
            ExternalTask securityExternal = fixture.waitingForOutput(
                started.id(),
                "securityDecision"
            );

            assertCompleted(afterFrontend, tasks, "security-stage");
            assertEquals(State.Type.COMPLETED, securityCheck.state().current());
            assertEquals(State.Type.WAITING, securityApprove.state().current());
            assertEquals(
                securityCheck.id(),
                securityApprove.parentId().orElseThrow()
            );
            assertEquals(
                ExternalTaskStatus.WAITING,
                securityExternal.status()
            );
            assertNoRun(afterFrontend, tasks.get("publish-artifact"));
            assertNoRun(afterFrontend, tasks.get("notify-result"));

            Execution completed = fixture.externalTaskService().complete(
                fixture.session(),
                securityExternal.id(),
                Map.of("securityDecision", "APPROVED")
            );

            assertEquals(
                Map.of("securityDecision", "APPROVED"),
                run(completed, tasks.get("security-approve")).outputs()
            );
            assertEquals(1, countRuns(
                completed,
                tasks.get("publish-artifact")
            ));
            assertEquals(1, countRuns(
                completed,
                tasks.get("notify-result")
            ));
            // PASS-S2-03
            assertEquals(State.Type.COMPLETED, completed.state().current());
            assertEquals(COMPLETE_RUN_ORDER, taskKeys(completed, flow));
            assertEquals(12, completed.taskRuns().size());
            assertEquals(
                12,
                completed.taskRuns().stream()
                    .map(TaskRun::id)
                    .distinct()
                    .count()
            );
            assertTrue(completed.taskRuns().stream().allMatch(
                taskRun -> taskRun.state().current() == State.Type.COMPLETED
            ));
            assertRuntimeTopology(completed, tasks);
            assertEquals(
                Set.of(
                    backendExternal.id(),
                    frontendExternal.id(),
                    securityExternal.id()
                ),
                Set.of(
                    externalTask(fixture, backendExternal.id()).id(),
                    externalTask(fixture, frontendExternal.id()).id(),
                    externalTask(fixture, securityExternal.id()).id()
                )
            );
            assertEquals(
                ExternalTaskStatus.COMPLETED,
                externalTask(fixture, securityExternal.id()).status()
            );
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s3RejectsDuplicateKeyAcrossNestedBranchesAtomically() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            FlowDraft draft = fixture.flowService().saveDraft(
                fixture.session(),
                duplicateNestedKeyYaml("uc07-s3-flow")
            );
            long lockVersion = draft.lockVersion();
            int executionCount = fixture.executionService().executions(
                fixture.session()
            ).size();

            // PASS-S3-01
            WorkflowException exception = assertThrows(
                WorkflowException.class,
                () -> fixture.flowService().deploy(
                    fixture.session(),
                    draft.id()
                )
            );
            assertTrue(exception.getMessage().contains(
                "Duplicate Task key: backend-review"
            ));

            FlowDraft reloaded = fixture.flowService().draft(
                fixture.session(),
                draft.id()
            ).orElseThrow();

            // PASS-S3-02
            assertEquals(lockVersion, reloaded.lockVersion());
            assertEquals(draft.raw(), reloaded.raw());
            assertTrue(fixture.flowService().flow(
                fixture.session(),
                draft.id(),
                1L
            ).isEmpty());
            // PASS-S3-02
            assertEquals(
                executionCount,
                fixture.executionService().executions(fixture.session()).size()
            );
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s4CancelsAllDeepWaitingBranchesAndRejectsResume() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                baselineYaml("uc07-s4-flow")
            );
            Map<String, Task> tasks = tasksByKey(flow);
            Execution started = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            TaskRun backendReview = run(
                started,
                tasks.get("backend-review")
            );
            TaskRun frontendReview = run(
                started,
                tasks.get("frontend-review")
            );
            fixture.restartServer();
            ExternalTask backendExternal = fixture.waitingForOutput(
                started.id(),
                "backendResult"
            );
            ExternalTask frontendExternal = fixture.waitingForOutput(
                started.id(),
                "frontendResult"
            );

            Execution canceled = fixture.executionService().cancel(
                fixture.session(),
                started.id()
            );

            // PASS-S4-01
            assertEquals(State.Type.TERMINATED, canceled.state().current());
            assertEquals(
                State.Type.TERMINATED,
                run(canceled, tasks.get("backend-review")).state().current()
            );
            assertEquals(
                State.Type.TERMINATED,
                run(canceled, tasks.get("frontend-review")).state().current()
            );
            assertEquals(
                ExternalTaskStatus.CANCELED,
                externalTask(fixture, backendExternal.id()).status()
            );
            assertEquals(
                ExternalTaskStatus.CANCELED,
                externalTask(fixture, frontendExternal.id()).status()
            );
            assertCompleted(canceled, tasks, "receive-request");
            assertCompleted(canceled, tasks, "prepare-release");
            assertCompleted(canceled, tasks, "backend-build");
            assertCompleted(canceled, tasks, "frontend-build");

            Execution canceledSnapshot = execution(fixture, started.id());
            ExternalTask backendSnapshot = externalTask(
                fixture,
                backendExternal.id()
            );
            ExternalTask frontendSnapshot = externalTask(
                fixture,
                frontendExternal.id()
            );

            // PASS-S4-02
            assertThrows(
                WorkflowException.class,
                () -> fixture.externalTaskService().complete(
                    fixture.session(),
                    backendExternal.id(),
                    Map.of("backendResult", "PASS")
                )
            );
            assertThrows(
                WorkflowException.class,
                () -> fixture.externalTaskService().complete(
                    fixture.session(),
                    frontendExternal.id(),
                    Map.of("frontendResult", "PASS")
                )
            );

            Execution afterRejected = execution(fixture, started.id());

            // PASS-S4-02
            assertExecutionSnapshot(canceledSnapshot, afterRejected);
            assertExternalTaskSnapshot(
                backendSnapshot,
                externalTask(fixture, backendExternal.id())
            );
            assertExternalTaskSnapshot(
                frontendSnapshot,
                externalTask(fixture, frontendExternal.id())
            );
            assertNoRunsFrom(afterRejected, flow, "integrate-results");
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s5ReversedDeepBranchResumeOrderKeepsEquivalentHistory() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                baselineYaml("uc07-s5-flow")
            );
            Map<String, Task> tasks = tasksByKey(flow);
            Execution firstStarted = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            Execution secondStarted = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            assertEquals(
                taskKeys(firstStarted, flow),
                taskKeys(secondStarted, flow)
            );
            assertEquals(
                runtimeTopology(firstStarted, tasks),
                runtimeTopology(secondStarted, tasks)
            );

            fixture.restartServer();
            ExternalTask firstBackend = fixture.waitingForOutput(
                firstStarted.id(),
                "backendResult"
            );
            ExternalTask firstFrontend = fixture.waitingForOutput(
                firstStarted.id(),
                "frontendResult"
            );
            ExternalTask secondBackend = fixture.waitingForOutput(
                secondStarted.id(),
                "backendResult"
            );
            ExternalTask secondFrontend = fixture.waitingForOutput(
                secondStarted.id(),
                "frontendResult"
            );

            Execution firstAfterOne = fixture.externalTaskService().complete(
                fixture.session(),
                firstBackend.id(),
                Map.of("backendResult", "PASS")
            );
            Execution secondAfterOne = fixture.externalTaskService().complete(
                fixture.session(),
                secondFrontend.id(),
                Map.of("frontendResult", "PASS")
            );

            // PASS-S5-01
            assertFirstCompletionState(
                fixture,
                firstAfterOne,
                tasks,
                "backend-review",
                "backendResult",
                "frontend-review",
                firstFrontend.id()
            );
            assertFirstCompletionState(
                fixture,
                secondAfterOne,
                tasks,
                "frontend-review",
                "frontendResult",
                "backend-review",
                secondBackend.id()
            );

            fixture.restartServer();
            firstFrontend = fixture.waitingForOutput(
                firstStarted.id(),
                "frontendResult"
            );
            secondBackend = fixture.waitingForOutput(
                secondStarted.id(),
                "backendResult"
            );
            Execution firstAtSecurity =
                fixture.externalTaskService().complete(
                    fixture.session(),
                    firstFrontend.id(),
                    Map.of("frontendResult", "PASS")
                );
            Execution secondAtSecurity =
                fixture.externalTaskService().complete(
                    fixture.session(),
                    secondBackend.id(),
                    Map.of("backendResult", "PASS")
                );

            // PASS-S5-02
            assertIntegration(firstAtSecurity, tasks);
            assertIntegration(secondAtSecurity, tasks);

            fixture.restartServer();
            ExternalTask firstSecurity = fixture.waitingForOutput(
                firstStarted.id(),
                "securityDecision"
            );
            ExternalTask secondSecurity = fixture.waitingForOutput(
                secondStarted.id(),
                "securityDecision"
            );
            Execution firstCompleted =
                fixture.externalTaskService().complete(
                    fixture.session(),
                    firstSecurity.id(),
                    Map.of("securityDecision", "APPROVED")
                );
            Execution secondCompleted =
                fixture.externalTaskService().complete(
                    fixture.session(),
                    secondSecurity.id(),
                    Map.of("securityDecision", "APPROVED")
                );

            // PASS-S5-02
            assertEquals(
                taskKeys(firstCompleted, flow),
                taskKeys(secondCompleted, flow)
            );
            assertEquals(
                runtimeTopology(firstCompleted, tasks),
                runtimeTopology(secondCompleted, tasks)
            );
            tasks.values().forEach(task -> {
                assertEquals(1, countRuns(firstCompleted, task));
                assertEquals(1, countRuns(secondCompleted, task));
            });
            assertEquals(
                run(firstCompleted, tasks.get("integrate-results")).inputs(),
                run(secondCompleted, tasks.get("integrate-results")).inputs()
            );
            // PASS-S5-03
            assertEquals(State.Type.COMPLETED, firstCompleted.state().current());
            assertEquals(State.Type.COMPLETED, secondCompleted.state().current());
            assertNotEquals(firstCompleted.id(), secondCompleted.id());
            assertTrue(disjointIds(
                firstCompleted.taskRuns().stream().map(TaskRun::id).toList(),
                secondCompleted.taskRuns().stream().map(TaskRun::id).toList()
            ));
            assertTrue(disjointIds(
                List.of(
                    firstBackend.id(),
                    firstFrontend.id(),
                    firstSecurity.id()
                ),
                List.of(
                    secondBackend.id(),
                    secondFrontend.id(),
                    secondSecurity.id()
                )
            ));
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s6MovedGrandchildUsesDeployedDirectParent() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                movedBackendReviewYaml("uc07-s6-flow")
            );
            Map<String, Task> tasks = tasksByKey(flow);

            // PASS-S6-01
            assertEquals(
                tasks.get("prepare-release").id(),
                tasks.get("backend-review").parentId().orElseThrow()
            );
            assertNotEquals(
                tasks.get("backend-build").id(),
                tasks.get("backend-review").parentId().orElseThrow()
            );
            assertEquals(
                tasks.get("frontend-build").id(),
                tasks.get("frontend-review").parentId().orElseThrow()
            );

            Execution execution = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            TaskRun prepare = run(
                execution,
                tasks.get("prepare-release")
            );
            TaskRun backendBuild = run(
                execution,
                tasks.get("backend-build")
            );
            TaskRun backendReview = run(
                execution,
                tasks.get("backend-review")
            );
            TaskRun frontendBuild = run(
                execution,
                tasks.get("frontend-build")
            );
            TaskRun frontendReview = run(
                execution,
                tasks.get("frontend-review")
            );

            // PASS-S6-02
            assertEquals(
                prepare.id(),
                backendReview.parentId().orElseThrow()
            );
            assertNotEquals(
                backendBuild.id(),
                backendReview.parentId().orElseThrow()
            );
            assertEquals(
                frontendBuild.id(),
                frontendReview.parentId().orElseThrow()
            );

            fixture.restartServer();
            assertEquals(
                ExternalTaskStatus.WAITING,
                fixture.waitingForOutput(
                    execution.id(),
                    "backendResult"
                ).status()
            );
            assertEquals(
                ExternalTaskStatus.WAITING,
                fixture.waitingForOutput(
                    execution.id(),
                    "frontendResult"
                ).status()
            );

            fixture.externalTaskService().complete(
                fixture.session(),
                fixture.waitingForOutput(
                    execution.id(),
                    "backendResult"
                ).id(),
                Map.of("backendResult", "PASS")
            );
            fixture.restartServer();
            fixture.externalTaskService().complete(
                fixture.session(),
                fixture.waitingForOutput(
                    execution.id(),
                    "frontendResult"
                ).id(),
                Map.of("frontendResult", "PASS")
            );
            fixture.restartServer();
            Execution completed =
                fixture.externalTaskService().complete(
                    fixture.session(),
                    fixture.waitingForOutput(
                        execution.id(),
                        "securityDecision"
                    ).id(),
                    Map.of("securityDecision", "APPROVED")
                );

            // PASS-S6-03
            assertEquals(State.Type.COMPLETED, completed.state().current());
            tasks.values().forEach(task ->
                assertEquals(1, countRuns(completed, task))
            );
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s7OrdinaryChildrenWaitForPreviousSubtreeToSettle() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                serialApprovalYaml("uc07-s7-flow")
            );
            Map<String, Task> tasks = tasksByKey(flow);
            Execution waiting = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );

            assertEquals(State.Type.WAITING, waiting.state().current());
            assertEquals(
                List.of("start", "approval"),
                taskKeys(waiting, flow)
            );
            assertNoRun(waiting, tasks.get("approved"));
            assertNoRun(waiting, tasks.get("approved-finish"));
            assertNoRun(waiting, tasks.get("rejected"));
            assertNoRun(waiting, tasks.get("serial-finish"));

            fixture.restartServer();
            ExternalTask approval = fixture.waitingForOutput(
                waiting.id(),
                "decision"
            );
            Execution completed = fixture.externalTaskService().complete(
                fixture.session(),
                approval.id(),
                Map.of("decision", "APPROVED")
            );

            assertEquals(State.Type.COMPLETED, completed.state().current());
            assertEquals(
                List.of(
                    "start",
                    "approval",
                    "approved",
                    "approved-finish",
                    "serial-finish"
                ),
                taskKeys(completed, flow)
            );
            assertNoRun(completed, tasks.get("rejected"));
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    private static void assertDefinitionTopology(Map<String, Task> tasks) {
        assertTrue(tasks.get("receive-request").parentId().isEmpty());
        assertTrue(tasks.get("prepare-release").parentId().isEmpty());
        assertEquals(
            tasks.get("prepare-release").id(),
            tasks.get("backend-build").parentId().orElseThrow()
        );
        assertEquals(
            tasks.get("backend-build").id(),
            tasks.get("backend-review").parentId().orElseThrow()
        );
        assertEquals(
            tasks.get("prepare-release").id(),
            tasks.get("frontend-build").parentId().orElseThrow()
        );
        assertEquals(
            tasks.get("frontend-build").id(),
            tasks.get("frontend-review").parentId().orElseThrow()
        );
        assertTrue(tasks.get("integrate-results").parentId().isEmpty());
        assertTrue(tasks.get("security-stage").parentId().isEmpty());
        assertEquals(
            tasks.get("security-stage").id(),
            tasks.get("security-check").parentId().orElseThrow()
        );
        assertEquals(
            tasks.get("security-check").id(),
            tasks.get("security-approve").parentId().orElseThrow()
        );
        assertTrue(tasks.get("publish-artifact").parentId().isEmpty());
        assertTrue(tasks.get("notify-result").parentId().isEmpty());
    }

    private static void assertRuntimeTopology(
        Execution execution,
        Map<String, Task> tasks
    ) {
        Map<String, String> expected = new LinkedHashMap<>();
        execution.taskRuns().forEach(taskRun -> {
            Task task = taskById(tasks, taskRun.taskId());
            expected.put(task.key(), task.parentId()
                .map(parentId -> tasks.values().stream()
                    .filter(parent -> parent.id().equals(parentId))
                    .map(Task::key)
                    .findFirst()
                    .orElseThrow())
                .orElse(null));
        });
        assertEquals(expected, runtimeTopology(execution, tasks));
    }

    private static Map<String, String> runtimeTopology(
        Execution execution,
        Map<String, Task> tasks
    ) {
        Map<String, TaskRun> runsByTaskId = new LinkedHashMap<>();
        execution.taskRuns().forEach(taskRun ->
            runsByTaskId.put(taskRun.taskId(), taskRun)
        );
        Map<String, String> topology = new LinkedHashMap<>();
        execution.taskRuns().forEach(taskRun -> {
            String key = taskById(tasks, taskRun.taskId()).key();
            String parentKey = taskRun.parentId()
                .map(parentRunId -> taskById(
                    tasks,
                    runsByTaskId.values().stream()
                        .filter(parent -> parent.id().equals(parentRunId))
                        .findFirst()
                        .orElseThrow()
                        .taskId()
                ).key())
                .orElse(null);
            topology.put(key, parentKey);
        });
        return topology;
    }

    private static void assertFirstCompletionState(
        WorkflowUcFixture fixture,
        Execution execution,
        Map<String, Task> tasks,
        String completedKey,
        String outputKey,
        String waitingKey,
        String waitingExternalTaskId
    ) {
        assertEquals(
            Map.of(outputKey, "PASS"),
            run(execution, tasks.get(completedKey)).outputs()
        );
        assertEquals(
            State.Type.WAITING,
            run(execution, tasks.get(waitingKey)).state().current()
        );
        assertEquals(
            ExternalTaskStatus.WAITING,
            externalTask(fixture, waitingExternalTaskId).status()
        );
        assertNoRun(execution, tasks.get("integrate-results"));
    }

    private static void assertIntegration(
        Execution execution,
        Map<String, Task> tasks
    ) {
        assertEquals(1, countRuns(
            execution,
            tasks.get("integrate-results")
        ));
        assertEquals(
            Map.of(
                "dependOnOutputs",
                Map.of(
                    "backend-review",
                    Map.of("backendResult", "PASS"),
                    "frontend-review",
                    Map.of("frontendResult", "PASS")
                )
            ),
            run(execution, tasks.get("integrate-results")).inputs()
        );
    }

    private static void assertCompleted(
        Execution execution,
        Map<String, Task> tasks,
        String taskKey
    ) {
        assertEquals(
            State.Type.COMPLETED,
            run(execution, tasks.get(taskKey)).state().current()
        );
    }

    private static void assertExecutionSnapshot(
        Execution expected,
        Execution actual
    ) {
        assertEquals(expected.state().current(), actual.state().current());
        assertEquals(expected.lockVersion(), actual.lockVersion());
        assertEquals(expected.taskRuns().size(), actual.taskRuns().size());
        for (int index = 0; index < expected.taskRuns().size(); index++) {
            TaskRun expectedRun = expected.taskRuns().get(index);
            TaskRun actualRun = actual.taskRuns().get(index);
            assertEquals(expectedRun.id(), actualRun.id());
            assertEquals(expectedRun.state().current(), actualRun.state().current());
            assertEquals(expectedRun.inputs(), actualRun.inputs());
            assertEquals(expectedRun.outputs(), actualRun.outputs());
        }
    }

    private static void assertExternalTaskSnapshot(
        ExternalTask expected,
        ExternalTask actual
    ) {
        assertEquals(expected.id(), actual.id());
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.outputs(), actual.outputs());
        assertEquals(expected.lockVersion(), actual.lockVersion());
    }

    private static Execution execution(
        WorkflowUcFixture fixture,
        String executionId
    ) {
        return fixture.executionService().execution(
            fixture.session(),
            executionId
        ).orElseThrow();
    }

    private static ExternalTask externalTask(
        WorkflowUcFixture fixture,
        String externalTaskId
    ) {
        return fixture.externalTaskService().externalTask(
            fixture.session(),
            externalTaskId
        ).orElseThrow();
    }

    private static Map<String, Task> tasksByKey(Flow flow) {
        Map<String, Task> result = new LinkedHashMap<>();
        flatten(flow.tasks()).forEach(task -> result.put(task.key(), task));
        return result;
    }

    private static Task taskById(
        Map<String, Task> tasks,
        String taskId
    ) {
        return tasks.values().stream()
            .filter(task -> task.id().equals(taskId))
            .findFirst()
            .orElseThrow();
    }

    private static List<Task> flatten(List<Task> tasks) {
        return tasks.stream()
            .flatMap(task -> java.util.stream.Stream.concat(
                java.util.stream.Stream.of(task),
                flatten(task.tasks()).stream()
            ))
            .toList();
    }

    private static TaskRun run(Execution execution, Task task) {
        return execution.taskRuns().stream()
            .filter(taskRun -> taskRun.taskId().equals(task.id()))
            .findFirst()
            .orElseThrow();
    }

    private static void assertNoRun(Execution execution, Task task) {
        assertEquals(0, countRuns(execution, task));
    }

    private static long countRuns(Execution execution, Task task) {
        return execution.taskRuns().stream()
            .filter(taskRun -> taskRun.taskId().equals(task.id()))
            .count();
    }

    private static List<String> taskKeys(
        Execution execution,
        Flow flow
    ) {
        Map<String, Task> tasks = tasksByKey(flow);
        return execution.taskRuns().stream()
            .map(taskRun -> taskById(tasks, taskRun.taskId()).key())
            .toList();
    }

    private static List<String> taskKeys(List<Task> tasks) {
        return flatten(tasks).stream().map(Task::key).toList();
    }

    private static void assertNoRunsFrom(
        Execution execution,
        Flow flow,
        String firstForbiddenKey
    ) {
        Map<String, Task> tasks = tasksByKey(flow);
        int start = COMPLETE_RUN_ORDER.indexOf(firstForbiddenKey);
        COMPLETE_RUN_ORDER.subList(start, COMPLETE_RUN_ORDER.size())
            .forEach(key -> assertNoRun(execution, tasks.get(key)));
    }

    private static boolean disjointIds(
        List<String> first,
        List<String> second
    ) {
        Set<String> firstIds = new HashSet<>(first);
        return second.stream().noneMatch(firstIds::contains);
    }

    private static String duplicateNestedKeyYaml(String key) {
        return baselineYaml(key).replace(
            "- key: security-approve",
            "- key: backend-review"
        );
    }

    private static String baselineYaml(String key) {
        return """
            key: %s
            description: 两层嵌套 Task 长流程
            tasks:
              - key: receive-request
                type: AUTO

              - key: prepare-release
                type: PARALLEL
                tasks:
                  - key: backend-build
                    type: AUTO
                    tasks:
                      - key: backend-review
                        type: PAUSE
                        outputs:
                          - key: backendResult
                            type: STRING

                  - key: frontend-build
                    type: AUTO
                    tasks:
                      - key: frontend-review
                        type: PAUSE
                        outputs:
                          - key: frontendResult
                            type: STRING

              - key: integrate-results
                type: AUTO
                dependOn:
                  - backend-review
                  - frontend-review

              - key: security-stage
                type: AUTO
                tasks:
                  - key: security-check
                    type: AUTO
                    tasks:
                      - key: security-approve
                        type: PAUSE
                        outputs:
                          - key: securityDecision
                            type: STRING

              - key: publish-artifact
                type: AUTO

              - key: notify-result
                type: AUTO
            """.formatted(key);
    }

    private static String movedBackendReviewYaml(String key) {
        return """
            key: %s
            description: 上移 backend review 的两层流程
            tasks:
              - key: receive-request
                type: AUTO

              - key: prepare-release
                type: PARALLEL
                tasks:
                  - key: backend-build
                    type: AUTO

                  - key: backend-review
                    type: PAUSE
                    outputs:
                      - key: backendResult
                        type: STRING

                  - key: frontend-build
                    type: AUTO
                    tasks:
                      - key: frontend-review
                        type: PAUSE
                        outputs:
                          - key: frontendResult
                            type: STRING

              - key: integrate-results
                type: AUTO
                dependOn:
                  - backend-review
                  - frontend-review

              - key: security-stage
                type: AUTO
                tasks:
                  - key: security-check
                    type: AUTO
                    tasks:
                      - key: security-approve
                        type: PAUSE
                        outputs:
                          - key: securityDecision
                            type: STRING

              - key: publish-artifact
                type: AUTO

              - key: notify-result
                type: AUTO
            """.formatted(key);
    }

    private static String serialApprovalYaml(String key) {
        return """
            key: %s
            description: 普通同级子任务默认串行
            tasks:
              - key: start
                type: AUTO
                tasks:
                  - key: approval
                    type: PAUSE
                    outputs:
                      - key: decision
                        type: STRING
                    tasks:
                      - key: approved
                        type: AUTO
                        route: outputs.decision == "APPROVED"
                        tasks:
                          - key: approved-finish
                            type: AUTO
                      - key: rejected
                        type: AUTO
                        route: outputs.decision == "REJECTED"
                  - key: serial-finish
                    type: AUTO
            """.formatted(key);
    }
}
