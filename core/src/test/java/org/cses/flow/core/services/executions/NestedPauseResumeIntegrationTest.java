package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
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
import static org.cses.flow.core.services.executions.WorkflowUcFixture.PausedTaskRunRef;

class NestedPauseResumeIntegrationTest {

    private static final List<String> INITIAL_RUN_ORDER = List.of(
        "receive-request",
        "prepare-release",
        "backend-build",
        "frontend-build",
        "backend-review",
        "frontend-review",
        "create-backend-review",
        "create-frontend-review"
    );

    private static final List<String> COMPLETE_RUN_ORDER = List.of(
        "receive-request",
        "prepare-release",
        "backend-build",
        "frontend-build",
        "backend-review",
        "frontend-review",
        "create-backend-review",
        "create-frontend-review",
        "integrate-results",
        "security-stage",
        "security-check",
        "security-approve",
        "create-security-approval",
        "publish-artifact",
        "notify-result"
    );

    @Test
    void resumesTwoLevelNestedFlow() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                baselineYaml("nested-resume-two-level-flow")
            );
            Map<String, Task> tasks = tasksByKey(flow);

            assertEquals(15, tasks.size());
            assertEquals(15, new HashSet<>(tasks.keySet()).size());
            Set<String> taskIds = new HashSet<>();
            tasks.values().forEach(task -> {
                assertNotNull(task.id());
                assertFalse(task.id().isBlank());
                assertTrue(taskIds.add(task.id()));
            });
            assertDefinitionTopology(flow);

            Execution execution = fixture.startAndAwait(flow);

            assertEquals(
                1,
                fixture.executionService().executions(fixture.session()).size()
            );
            assertEquals(flow.key(), execution.flowKey());
            assertEquals(flow.reversion(), execution.flowVersion());
            assertEquals(State.Type.PAUSED, execution.state().current());
            assertEquals(INITIAL_RUN_ORDER, taskKeys(execution, flow));
            assertEquals(8, execution.taskRuns().size());

            TaskRun backendReview = run(execution, tasks.get("backend-review"));
            TaskRun frontendReview = run(
                execution,
                tasks.get("frontend-review")
            );
            fixture.restartServer();
            PausedTaskRunRef backendPause = fixture.waitingForOutput(
                execution.id(),
                "backendResult"
            );
            PausedTaskRunRef frontendPause = fixture.waitingForOutput(
                execution.id(),
                "frontendResult"
            );

            assertCompleted(execution, tasks, "receive-request");
            assertEquals(
                State.Type.PAUSED,
                run(execution, tasks.get("prepare-release"))
                    .state()
                    .current()
            );
            assertEquals(
                State.Type.PAUSED,
                run(execution, tasks.get("backend-build")).state().current()
            );
            assertEquals(
                State.Type.PAUSED,
                run(execution, tasks.get("frontend-build")).state().current()
            );
            assertEquals(State.Type.PAUSED, backendReview.state().current());
            assertEquals(State.Type.PAUSED, frontendReview.state().current());
            assertEquals(
                State.Type.PAUSED,
                fixture.taskRun(backendPause).state().current()
            );
            assertEquals(
                State.Type.PAUSED,
                fixture.taskRun(frontendPause).state().current()
            );
            assertNotEquals(
                backendPause.taskRunId(),
                frontendPause.taskRunId()
            );
            assertEquals(execution.id(), backendPause.executionId());
            assertEquals(execution.id(), frontendPause.executionId());
            assertEquals(backendReview.id(), backendPause.taskRunId());
            assertEquals(frontendReview.id(), frontendPause.taskRunId());

            assertRuntimeTopology(execution, flow);
            assertNoRunsFrom(execution, flow, "integrate-results");

            Execution afterBackend = fixture.resume(
                backendPause,
                Map.of("backendResult", "PASS")
            );
            assertNoRun(afterBackend, tasks.get("integrate-results"));

            fixture.restartServer();
            frontendPause = fixture.waitingForOutput(
                execution.id(),
                "frontendResult"
            );
            Execution atSecurity = fixture.resume(
                frontendPause,
                Map.of("frontendResult", "PASS")
            );
            assertIntegration(atSecurity, tasks);

            fixture.restartServer();
            PausedTaskRunRef securityPause = fixture.waitingForOutput(
                execution.id(),
                "securityDecision"
            );
            Execution completed = fixture.resume(
                securityPause,
                Map.of("securityDecision", "APPROVED")
            );
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(15, completed.taskRuns().size());
            assertTrue(completed.taskRuns().stream()
                .allMatch(run ->
                    run.state().current() == State.Type.SUCCESS
                ));
            assertEquals(COMPLETE_RUN_ORDER, taskKeys(completed, flow));
            tasks.values().forEach(task ->
                assertEquals(1, countRuns(completed, task))
            );
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void resumesLongFlowAcrossThreeDeepWaitingPoints() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                baselineYaml("nested-resume-three-points-flow")
            );
            Map<String, Task> tasks = tasksByKey(flow);
            Execution started = fixture.startAndAwait(flow);
            // server 的公开等待任务查询。
            fixture.restartServer();
            PausedTaskRunRef backendPause = fixture.waitingForOutput(
                started.id(),
                "backendResult"
            );
            PausedTaskRunRef frontendPause = fixture.waitingForOutput(
                started.id(),
                "frontendResult"
            );

            Execution afterBackend = fixture.resume(
                backendPause,
                Map.of("backendResult", "PASS")
            );

            assertEquals(
                State.Type.SUCCESS,
                afterBackend.requireTaskRun(
                    backendPause.taskRunId()
                ).state().current()
            );
            assertEquals(
                Map.of("backendResult", "PASS"),
                run(afterBackend, tasks.get("backend-review")).outputs()
            );
            assertEquals(
                State.Type.PAUSED,
                run(afterBackend, tasks.get("frontend-review")).state().current()
            );
            assertEquals(
                State.Type.PAUSED,
                afterBackend.requireTaskRun(
                    frontendPause.taskRunId()
                ).state().current()
            );
            assertNoRun(afterBackend, tasks.get("integrate-results"));

            fixture.restartServer();
            frontendPause = fixture.waitingForOutput(
                started.id(),
                "frontendResult"
            );
            Execution afterFrontend = fixture.resume(
                frontendPause,
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
            assertEquals(State.Type.SUCCESS, integration.state().current());
            assertEquals(
                Map.of(
                    "outputs",
                    Map.of(
                        "receive-request", Map.of(),
                        "prepare-release", Map.of()
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
            PausedTaskRunRef securityPause = fixture.waitingForOutput(
                started.id(),
                "securityDecision"
            );

            assertEquals(
                State.Type.PAUSED,
                run(afterFrontend, tasks.get("security-stage"))
                    .state().current()
            );
            assertEquals(State.Type.PAUSED, securityCheck.state().current());
            assertEquals(State.Type.PAUSED, securityApprove.state().current());
            assertEquals(
                securityCheck.id(),
                securityApprove.parentId().orElseThrow()
            );
            assertEquals(
                State.Type.PAUSED,
                fixture.taskRun(securityPause).state().current()
            );
            assertNoRun(afterFrontend, tasks.get("publish-artifact"));
            assertNoRun(afterFrontend, tasks.get("notify-result"));

            Execution completed = fixture.resume(
                securityPause,
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
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(COMPLETE_RUN_ORDER, taskKeys(completed, flow));
            assertEquals(15, completed.taskRuns().size());
            assertEquals(
                15,
                completed.taskRuns().stream()
                    .map(TaskRun::id)
                    .distinct()
                    .count()
            );
            assertTrue(completed.taskRuns().stream().allMatch(
                taskRun -> taskRun.state().current() == State.Type.SUCCESS
            ));
            assertRuntimeTopology(completed, flow);
            assertEquals(
                Set.of(
                    backendPause.taskRunId(),
                    frontendPause.taskRunId(),
                    securityPause.taskRunId()
                ),
                Set.of(
                    completed.requireTaskRun(
                        backendPause.taskRunId()
                    ).id(),
                    completed.requireTaskRun(
                        frontendPause.taskRunId()
                    ).id(),
                    completed.requireTaskRun(
                        securityPause.taskRunId()
                    ).id()
                )
            );
            assertEquals(
                State.Type.SUCCESS,
                completed.requireTaskRun(
                    securityPause.taskRunId()
                ).state().current()
            );
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void cancellationStopsDeepWaitingBranchesAndRejectsResume() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                baselineYaml("nested-resume-cancellation-flow")
            );
            Map<String, Task> tasks = tasksByKey(flow);
            Execution started = fixture.startAndAwait(flow);
            TaskRun backendReview = run(
                started,
                tasks.get("backend-review")
            );
            TaskRun frontendReview = run(
                started,
                tasks.get("frontend-review")
            );
            fixture.restartServer();
            PausedTaskRunRef backendPause = fixture.waitingForOutput(
                started.id(),
                "backendResult"
            );
            PausedTaskRunRef frontendPause = fixture.waitingForOutput(
                started.id(),
                "frontendResult"
            );

            Execution canceled = fixture.cancel(started.id());

            assertEquals(State.Type.KILLED, canceled.state().current());
            assertEquals(
                State.Type.KILLED,
                run(canceled, tasks.get("backend-review")).state().current()
            );
            assertEquals(
                State.Type.KILLED,
                run(canceled, tasks.get("frontend-review")).state().current()
            );
            assertEquals(
                State.Type.KILLED,
                canceled.requireTaskRun(
                    backendPause.taskRunId()
                ).state().current()
            );
            assertEquals(
                State.Type.KILLED,
                canceled.requireTaskRun(
                    frontendPause.taskRunId()
                ).state().current()
            );
            assertCompleted(canceled, tasks, "receive-request");
            assertEquals(
                State.Type.KILLED,
                run(canceled, tasks.get("prepare-release"))
                    .state()
                    .current()
            );
            assertEquals(
                State.Type.KILLED,
                run(canceled, tasks.get("backend-build")).state().current()
            );
            assertEquals(
                State.Type.KILLED,
                run(canceled, tasks.get("frontend-build")).state().current()
            );

            Execution canceledSnapshot = execution(fixture, started.id());
            TaskRun backendSnapshot = canceledSnapshot.requireTaskRun(
                backendPause.taskRunId()
            );
            TaskRun frontendSnapshot = canceledSnapshot.requireTaskRun(
                frontendPause.taskRunId()
            );

            assertThrows(
                WorkflowException.class,
                () -> fixture.resume(
                    backendPause,
                    Map.of("backendResult", "PASS")
                )
            );
            assertThrows(
                WorkflowException.class,
                () -> fixture.resume(
                    frontendPause,
                    Map.of("frontendResult", "PASS")
                )
            );

            Execution afterRejected = execution(fixture, started.id());

            assertExecutionSnapshot(canceledSnapshot, afterRejected);
            assertTaskRunSnapshot(
                backendSnapshot,
                afterRejected.requireTaskRun(backendPause.taskRunId())
            );
            assertTaskRunSnapshot(
                frontendSnapshot,
                afterRejected.requireTaskRun(frontendPause.taskRunId())
            );
            assertNoRunsFrom(afterRejected, flow, "integrate-results");
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void reversedDeepBranchResumeOrderKeepsEquivalentHistory() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                baselineYaml("nested-resume-reversed-order-flow")
            );
            Map<String, Task> tasks = tasksByKey(flow);
            Execution firstStarted = fixture.startAndAwait(flow);
            Execution secondStarted = fixture.startAndAwait(flow);
            assertEquals(
                taskKeys(firstStarted, flow),
                taskKeys(secondStarted, flow)
            );
            assertEquals(
                runtimeTopology(firstStarted, tasks),
                runtimeTopology(secondStarted, tasks)
            );

            fixture.restartServer();
            PausedTaskRunRef firstBackend = fixture.waitingForOutput(
                firstStarted.id(),
                "backendResult"
            );
            PausedTaskRunRef firstFrontend = fixture.waitingForOutput(
                firstStarted.id(),
                "frontendResult"
            );
            PausedTaskRunRef secondBackend = fixture.waitingForOutput(
                secondStarted.id(),
                "backendResult"
            );
            PausedTaskRunRef secondFrontend = fixture.waitingForOutput(
                secondStarted.id(),
                "frontendResult"
            );

            Execution firstAfterOne = fixture.resume(
                firstBackend,
                Map.of("backendResult", "PASS")
            );
            Execution secondAfterOne = fixture.resume(
                secondFrontend,
                Map.of("frontendResult", "PASS")
            );

            assertFirstCompletionState(
                fixture,
                firstAfterOne,
                tasks,
                "backend-review",
                "backendResult",
                "frontend-review",
                firstFrontend
            );
            assertFirstCompletionState(
                fixture,
                secondAfterOne,
                tasks,
                "frontend-review",
                "frontendResult",
                "backend-review",
                secondBackend
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
            Execution firstAtSecurity = fixture.resume(
                firstFrontend,
                Map.of("frontendResult", "PASS")
            );
            Execution secondAtSecurity = fixture.resume(
                secondBackend,
                Map.of("backendResult", "PASS")
            );

            assertIntegration(firstAtSecurity, tasks);
            assertIntegration(secondAtSecurity, tasks);

            fixture.restartServer();
            PausedTaskRunRef firstSecurity = fixture.waitingForOutput(
                firstStarted.id(),
                "securityDecision"
            );
            PausedTaskRunRef secondSecurity = fixture.waitingForOutput(
                secondStarted.id(),
                "securityDecision"
            );
            Execution firstCompleted = fixture.resume(
                firstSecurity,
                Map.of("securityDecision", "APPROVED")
            );
            Execution secondCompleted = fixture.resume(
                secondSecurity,
                Map.of("securityDecision", "APPROVED")
            );

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
            assertEquals(State.Type.SUCCESS, firstCompleted.state().current());
            assertEquals(State.Type.SUCCESS, secondCompleted.state().current());
            assertNotEquals(firstCompleted.id(), secondCompleted.id());
            assertTrue(disjointIds(
                firstCompleted.taskRuns().stream().map(TaskRun::id).toList(),
                secondCompleted.taskRuns().stream().map(TaskRun::id).toList()
            ));
            assertTrue(disjointIds(
                List.of(
                    firstBackend.taskRunId(),
                    firstFrontend.taskRunId(),
                    firstSecurity.taskRunId()
                ),
                List.of(
                    secondBackend.taskRunId(),
                    secondFrontend.taskRunId(),
                    secondSecurity.taskRunId()
                )
            ));
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void movedGrandchildUsesDeployedDirectParent() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                movedBackendReviewYaml("nested-resume-moved-grandchild-flow")
            );
            Map<String, Task> tasks = tasksByKey(flow);
            Map<String, String> topology = definitionTopology(flow);

            assertEquals(
                "prepare-release",
                topology.get("backend-review")
            );
            assertNotEquals(
                "backend-build",
                topology.get("backend-review")
            );
            assertEquals(
                "frontend-build",
                topology.get("frontend-review")
            );

            Execution execution = fixture.startAndAwait(flow);
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
                State.Type.PAUSED,
                fixture.taskRun(fixture.waitingForOutput(
                    execution.id(),
                    "backendResult"
                )).state().current()
            );
            assertEquals(
                State.Type.PAUSED,
                fixture.taskRun(fixture.waitingForOutput(
                    execution.id(),
                    "frontendResult"
                )).state().current()
            );

            fixture.resume(
                fixture.waitingForOutput(
                    execution.id(),
                    "backendResult"
                ),
                Map.of("backendResult", "PASS")
            );
            fixture.restartServer();
            fixture.resume(
                fixture.waitingForOutput(
                    execution.id(),
                    "frontendResult"
                ),
                Map.of("frontendResult", "PASS")
            );
            fixture.restartServer();
            Execution completed = fixture.resume(
                fixture.waitingForOutput(
                    execution.id(),
                    "securityDecision"
                ),
                Map.of("securityDecision", "APPROVED")
            );

            assertEquals(State.Type.SUCCESS, completed.state().current());
            tasks.values().forEach(task ->
                assertEquals(1, countRuns(completed, task))
            );
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void ordinaryChildrenWaitForPreviousSubtreeToSettle() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                serialApprovalYaml("nested-resume-serial-flow")
            );
            Map<String, Task> tasks = tasksByKey(flow);
            Execution waiting = fixture.startAndAwait(flow);

            assertEquals(State.Type.PAUSED, waiting.state().current());
            assertEquals(
                List.of("start", "approval", "create-approval"),
                taskKeys(waiting, flow)
            );
            assertNoRun(waiting, tasks.get("approved"));
            assertNoRun(waiting, tasks.get("approved-finish"));
            assertNoRun(waiting, tasks.get("rejected"));
            assertNoRun(waiting, tasks.get("serial-finish"));

            fixture.restartServer();
            PausedTaskRunRef approval = fixture.waitingForOutput(
                waiting.id(),
                "decision"
            );
            Execution completed = fixture.resume(
                approval,
                Map.of("decision", "APPROVED")
            );

            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(
                List.of(
                    "start",
                    "approval",
                    "create-approval",
                    "route-approval",
                    "approved",
                    "approved-finish",
                    "rejected",
                    "serial-finish"
                ),
                taskKeys(completed, flow)
            );
            assertEquals(
                State.Type.SUCCESS,
                run(completed, tasks.get("rejected")).state().current()
            );
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    private static void assertDefinitionTopology(Flow flow) {
        Map<String, String> topology = definitionTopology(flow);
        assertEquals(null, topology.get("receive-request"));
        assertEquals(null, topology.get("prepare-release"));
        assertEquals(
            "prepare-release",
            topology.get("backend-build")
        );
        assertEquals(
            "backend-build",
            topology.get("backend-review")
        );
        assertEquals(
            "backend-review",
            topology.get("create-backend-review")
        );
        assertEquals(
            "prepare-release",
            topology.get("frontend-build")
        );
        assertEquals(
            "frontend-build",
            topology.get("frontend-review")
        );
        assertEquals(
            "frontend-review",
            topology.get("create-frontend-review")
        );
        assertEquals(null, topology.get("integrate-results"));
        assertEquals(null, topology.get("security-stage"));
        assertEquals(
            "security-stage",
            topology.get("security-check")
        );
        assertEquals(
            "security-check",
            topology.get("security-approve")
        );
        assertEquals(
            "security-approve",
            topology.get("create-security-approval")
        );
        assertEquals(null, topology.get("publish-artifact"));
        assertEquals(null, topology.get("notify-result"));
    }

    private static void assertRuntimeTopology(
        Execution execution,
        Flow flow
    ) {
        Map<String, Task> tasks = tasksByKey(flow);
        Map<String, String> definitionTopology = definitionTopology(flow);
        Map<String, String> expected = new LinkedHashMap<>();
        execution.taskRuns().forEach(taskRun -> {
            Task task = taskById(tasks, taskRun.taskId());
            expected.put(task.key(), definitionTopology.get(task.key()));
        });
        assertEquals(expected, runtimeTopology(execution, tasks));
    }

    private static Map<String, String> definitionTopology(Flow flow) {
        Map<String, String> topology = new LinkedHashMap<>();
        appendDefinitionTopology(flow.tasks(), null, topology);
        return topology;
    }

    private static void appendDefinitionTopology(
        List<Task> tasks,
        String parentKey,
        Map<String, String> topology
    ) {
        for (Task task : tasks) {
            topology.put(task.key(), parentKey);
            appendDefinitionTopology(
                task.definitionChildren(),
                task.key(),
                topology
            );
        }
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
        PausedTaskRunRef waitingTaskRun
    ) {
        assertEquals(
            Map.of(outputKey, "PASS"),
            run(execution, tasks.get(completedKey)).outputs()
        );
        assertEquals(
            State.Type.PAUSED,
            run(execution, tasks.get(waitingKey)).state().current()
        );
        assertEquals(
            State.Type.PAUSED,
            fixture.taskRun(waitingTaskRun).state().current()
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
                "outputs",
                Map.of(
                    "receive-request", Map.of(),
                    "prepare-release", Map.of()
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
            State.Type.SUCCESS,
            run(execution, tasks.get(taskKey)).state().current()
        );
    }

    private static void assertExecutionSnapshot(
        Execution expected,
        Execution actual
    ) {
        assertEquals(expected.state().current(), actual.state().current());
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

    private static void assertTaskRunSnapshot(
        TaskRun expected,
        TaskRun actual
    ) {
        assertEquals(expected.id(), actual.id());
        assertEquals(expected.state(), actual.state());
        assertEquals(expected.inputs(), actual.inputs());
        assertEquals(expected.outputs(), actual.outputs());
        assertEquals(expected.error(), actual.error());
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
                flatten(task.definitionChildren()).stream()
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

    static String duplicateNestedKeyYaml(String key) {
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
                type: org.cses.flow.extensions.log.Log
                message: "test step"

              - key: prepare-release
                type: org.cses.flow.extensions.flow.Parallel
                tasks:
                  - key: backend-build
                    type: org.cses.flow.extensions.flow.Sequence
                    tasks:
                      - key: backend-review
                        type: org.cses.flow.extensions.flow.Pause
                        pause:
                          key: create-backend-review
                          type: org.cses.flow.extensions.log.Log
                          message: "test step"
                        resume:
                          - key: backendResult
                            type: STRING
                        outputs:
                          - key: backendResult
                            type: STRING

                  - key: frontend-build
                    type: org.cses.flow.extensions.flow.Sequence
                    tasks:
                      - key: frontend-review
                        type: org.cses.flow.extensions.flow.Pause
                        pause:
                          key: create-frontend-review
                          type: org.cses.flow.extensions.log.Log
                          message: "test step"
                        resume:
                          - key: frontendResult
                            type: STRING
                        outputs:
                          - key: frontendResult
                            type: STRING

              - key: integrate-results
                type: org.cses.flow.extensions.log.Log
                message: "test step"

              - key: security-stage
                type: org.cses.flow.extensions.flow.Sequence
                tasks:
                  - key: security-check
                    type: org.cses.flow.extensions.flow.Sequence
                    tasks:
                      - key: security-approve
                        type: org.cses.flow.extensions.flow.Pause
                        pause:
                          key: create-security-approval
                          type: org.cses.flow.extensions.log.Log
                          message: "test step"
                        resume:
                          - key: securityDecision
                            type: STRING
                        outputs:
                          - key: securityDecision
                            type: STRING

              - key: publish-artifact
                type: org.cses.flow.extensions.log.Log
                message: "test step"

              - key: notify-result
                type: org.cses.flow.extensions.log.Log
                message: "test step"
            """.formatted(key);
    }

    private static String movedBackendReviewYaml(String key) {
        return """
            key: %s
            description: 上移 backend review 的两层流程
            tasks:
              - key: receive-request
                type: org.cses.flow.extensions.log.Log
                message: "test step"

              - key: prepare-release
                type: org.cses.flow.extensions.flow.Parallel
                tasks:
                  - key: backend-build
                    type: org.cses.flow.extensions.log.Log
                    message: "test step"

                  - key: backend-review
                    type: org.cses.flow.extensions.flow.Pause
                    pause:
                      key: create-backend-review
                      type: org.cses.flow.extensions.log.Log
                      message: "test step"
                    resume:
                      - key: backendResult
                        type: STRING
                    outputs:
                      - key: backendResult
                        type: STRING

                  - key: frontend-build
                    type: org.cses.flow.extensions.flow.Sequence
                    tasks:
                      - key: frontend-review
                        type: org.cses.flow.extensions.flow.Pause
                        pause:
                          key: create-frontend-review
                          type: org.cses.flow.extensions.log.Log
                          message: "test step"
                        resume:
                          - key: frontendResult
                            type: STRING
                        outputs:
                          - key: frontendResult
                            type: STRING

              - key: integrate-results
                type: org.cses.flow.extensions.log.Log
                message: "test step"

              - key: security-stage
                type: org.cses.flow.extensions.flow.Sequence
                tasks:
                  - key: security-check
                    type: org.cses.flow.extensions.flow.Sequence
                    tasks:
                      - key: security-approve
                        type: org.cses.flow.extensions.flow.Pause
                        pause:
                          key: create-security-approval
                          type: org.cses.flow.extensions.log.Log
                          message: "test step"
                        resume:
                          - key: securityDecision
                            type: STRING
                        outputs:
                          - key: securityDecision
                            type: STRING

              - key: publish-artifact
                type: org.cses.flow.extensions.log.Log
                message: "test step"

              - key: notify-result
                type: org.cses.flow.extensions.log.Log
                message: "test step"
            """.formatted(key);
    }

    private static String serialApprovalYaml(String key) {
        return """
            key: %s
            description: 普通同级子任务默认串行
            tasks:
              - key: start
                type: org.cses.flow.extensions.flow.Sequence
                tasks:
                  - key: approval
                    type: org.cses.flow.extensions.flow.Pause
                    pause:
                      key: create-approval
                      type: org.cses.flow.extensions.log.Log
                      message: "test step"
                    resume:
                      - key: decision
                        type: STRING
                    outputs:
                      - key: decision
                        type: STRING
                  - key: route-approval
                    type: org.cses.flow.extensions.flow.Route
                    route: '{{ outputs.approval.decision }} == APPROVED'
                    tasks:
                      - key: approved
                        type: org.cses.flow.extensions.flow.Sequence
                        tasks:
                          - key: approved-finish
                            type: org.cses.flow.extensions.log.Log
                            message: "test step"
                  - key: rejected
                    type: org.cses.flow.extensions.flow.Route
                    route: '{{ outputs.approval.decision }} == REJECTED'
                  - key: serial-finish
                    type: org.cses.flow.extensions.log.Log
                    message: "test step"
            """.formatted(key);
    }

}
