package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.extensions.flow.Pause;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.cses.flow.core.services.executions.WorkflowUcFixture.PausedTaskRunRef;

/**
 * UC: docs/uc/flow/UC-07 用户处理包含多阶段 Pause 的 Flow.md
 */
class Uc07NestedTaskFlowTest {

    private static final List<String> STANDARD_RUN_ORDER = List.of(
        "prepare",
        "review-stage",
        "backend-review",
        "frontend-review",
        "create-backend-review",
        "create-frontend-review",
        "summarize-reviews",
        "security-stage",
        "security-approve",
        "create-security-approval",
        "publish-artifact",
        "notify-result"
    );

    @Test
    void s1CompletesTheMultiStagePauseFlow() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(standardYaml("uc07-s1-flow"));
            Execution started = fixture.startAndAwait(flow);
            String executionId = started.id();

            fixture.restartServer();
            Flow persisted = persistedFlow(fixture, flow);
            assertEquals(
                Set.of("backend-review", "frontend-review"),
                pausedKeys(fixture, persisted, executionId)
            );
            PausedTaskRunRef backend = fixture.waitingForOutput(
                executionId,
                "backendResult"
            );
            PausedTaskRunRef frontend = fixture.waitingForOutput(
                executionId,
                "frontendResult"
            );
            assertNotEquals(backend.taskRunId(), frontend.taskRunId());

            Execution afterBackend = resume(
                fixture,
                backend,
                Map.of("backendResult", "PASS")
            );
            assertEquals(State.Type.PAUSED, afterBackend.state().current());
            assertEquals(
                Map.of("backendResult", "PASS"),
                run(afterBackend, persisted, "backend-review").outputs()
            );
            assertEquals(
                State.Type.PAUSED,
                run(afterBackend, persisted, "frontend-review")
                    .state()
                    .current()
            );
            assertNoRun(afterBackend, persisted, "summarize-reviews");
            assertNoRun(afterBackend, persisted, "security-approve");

            fixture.restartServer();
            persisted = persistedFlow(fixture, flow);
            PausedTaskRunRef requeriedFrontend = fixture.waitingForOutput(
                executionId,
                "frontendResult"
            );
            assertEquals(frontend.taskRunId(), requeriedFrontend.taskRunId());
            Execution afterFrontend = resume(
                fixture,
                requeriedFrontend,
                Map.of("frontendResult", "PASS")
            );
            assertEquals(State.Type.PAUSED, afterFrontend.state().current());
            assertEquals(
                State.Type.SUCCESS,
                run(afterFrontend, persisted, "summarize-reviews")
                    .state()
                    .current()
            );
            assertEquals(
                Set.of("security-approve"),
                pausedKeys(fixture, persisted, executionId)
            );
            assertNoRun(afterFrontend, persisted, "publish-artifact");
            assertNoRun(afterFrontend, persisted, "notify-result");

            fixture.restartServer();
            persisted = persistedFlow(fixture, flow);
            PausedTaskRunRef security = fixture.waitingForOutput(
                executionId,
                "securityDecision"
            );
            Execution completed = resume(
                fixture,
                security,
                Map.of("securityDecision", "APPROVED")
            );

            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(STANDARD_RUN_ORDER, taskKeys(completed, persisted));
            assertAllTasksRunOnce(completed, persisted);
            assertEquals(
                Map.of("securityDecision", "APPROVED"),
                run(completed, persisted, "security-approve").outputs()
            );
            assertTrue(completed.activeTaskRuns().isEmpty());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s2RequeriesOnlyTheCurrentPauseAtEachStage() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(standardYaml("uc07-s2-flow"));
            Execution started = fixture.startAndAwait(flow);
            String executionId = started.id();

            fixture.restartServer();
            Flow persisted = persistedFlow(fixture, flow);
            PausedTaskRunRef backend = fixture.waitingForOutput(
                executionId,
                "backendResult"
            );
            PausedTaskRunRef frontend = fixture.waitingForOutput(
                executionId,
                "frontendResult"
            );
            assertEquals(
                Set.of("backend-review", "frontend-review"),
                pausedKeys(fixture, persisted, executionId)
            );

            resume(fixture, backend, Map.of("backendResult", "PASS"));
            fixture.restartServer();
            persisted = persistedFlow(fixture, flow);
            assertEquals(
                Set.of("frontend-review"),
                pausedKeys(fixture, persisted, executionId)
            );
            PausedTaskRunRef requeriedFrontend = fixture.waitingForOutput(
                executionId,
                "frontendResult"
            );
            assertEquals(frontend.taskRunId(), requeriedFrontend.taskRunId());
            resume(
                fixture,
                requeriedFrontend,
                Map.of("frontendResult", "PASS")
            );

            fixture.restartServer();
            persisted = persistedFlow(fixture, flow);
            assertEquals(
                Set.of("security-approve"),
                pausedKeys(fixture, persisted, executionId)
            );
            assertTrue(
                pausedKeys(fixture, persisted, executionId).stream()
                    .noneMatch(key -> key.equals("backend-review")
                        || key.equals("frontend-review"))
            );
            PausedTaskRunRef security = fixture.waitingForOutput(
                executionId,
                "securityDecision"
            );
            Execution completed = resume(
                fixture,
                security,
                Map.of("securityDecision", "APPROVED")
            );

            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertAllTasksRunOnce(completed, persisted);
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s3RejectsDuplicateKeyAcrossNestedBranchesAtomically() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow draft = fixture.flowService().save(
                fixture.session(),
                PublishFlowCommand.from(
                    duplicatePauseKeyYaml("uc07-s3-flow")
                )
            );
            int executionCount = fixture.executionService().executions(
                fixture.session()
            ).size();

            WorkflowException exception = assertThrows(
                WorkflowException.class,
                () -> fixture.flowService().save(
                    fixture.session(),
                    PublishFlowCommand.from(draft.key(), false)
                )
            );
            assertTrue(exception.getMessage().contains(
                "Duplicate Task key: backend-review"
            ));

            Flow reloaded = fixture.flowService().draft(
                fixture.session(),
                draft.key()
            ).orElseThrow();

            assertEquals(draft.source(), reloaded.source());
            assertTrue(fixture.flowService().flow(
                fixture.session(),
                draft.key(),
                1L
            ).isEmpty());
            assertEquals(
                executionCount,
                fixture.executionService().executions(fixture.session()).size()
            );
        }
    }

    @Test
    void s4CancelAtDeepParallelPausesRejectsBothFormerResumes() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(standardYaml("uc07-s4-flow"));
            Execution started = fixture.startAndAwait(flow);
            String executionId = started.id();

            fixture.restartServer();
            Flow persisted = persistedFlow(fixture, flow);
            PausedTaskRunRef backend = fixture.waitingForOutput(
                executionId,
                "backendResult"
            );
            PausedTaskRunRef frontend = fixture.waitingForOutput(
                executionId,
                "frontendResult"
            );
            Execution canceled = fixture.cancel(executionId);
            Execution canceledSnapshot = query(fixture, executionId);

            assertEquals(State.Type.KILLED, canceled.state().current());
            assertEquals(State.Type.KILLED, canceledSnapshot.state().current());
            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().resume(
                    fixture.session(),
                    backend.executionId(),
                    backend.taskRunId(),
                    Map.of("backendResult", "PASS")
                )
            );
            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().resume(
                    fixture.session(),
                    frontend.executionId(),
                    frontend.taskRunId(),
                    Map.of("frontendResult", "PASS")
                )
            );

            Execution afterRejected = query(fixture, executionId);
            assertUnchanged(canceledSnapshot, afterRejected);
            assertNoRun(afterRejected, persisted, "summarize-reviews");
            assertNoRun(afterRejected, persisted, "security-approve");
            assertNoRun(afterRejected, persisted, "publish-artifact");
            assertNoRun(afterRejected, persisted, "notify-result");
            assertTrue(afterRejected.activeTaskRuns().isEmpty());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s5ReversedParallelResumeOrderKeepsResultsAndHierarchy() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(standardYaml("uc07-s5-flow"));
            Execution firstStarted = fixture.startAndAwait(flow);
            Execution secondStarted = fixture.startAndAwait(flow);
            String firstId = firstStarted.id();
            String secondId = secondStarted.id();

            fixture.restartServer();
            Flow persisted = persistedFlow(fixture, flow);
            PausedTaskRunRef firstBackend = fixture.waitingForOutput(
                firstId,
                "backendResult"
            );
            PausedTaskRunRef firstFrontend = fixture.waitingForOutput(
                firstId,
                "frontendResult"
            );
            PausedTaskRunRef secondBackend = fixture.waitingForOutput(
                secondId,
                "backendResult"
            );
            PausedTaskRunRef secondFrontend = fixture.waitingForOutput(
                secondId,
                "frontendResult"
            );

            resume(
                fixture,
                firstBackend,
                Map.of("backendResult", "FIRST-BACKEND")
            );
            fixture.restartServer();
            persisted = persistedFlow(fixture, flow);
            assertEquals(
                Set.of("frontend-review"),
                pausedKeys(fixture, persisted, firstId)
            );

            resume(
                fixture,
                secondFrontend,
                Map.of("frontendResult", "SECOND-FRONTEND")
            );
            fixture.restartServer();
            persisted = persistedFlow(fixture, flow);
            assertEquals(
                Set.of("backend-review"),
                pausedKeys(fixture, persisted, secondId)
            );

            PausedTaskRunRef firstFrontendRequeried = fixture.waitingForOutput(
                firstId,
                "frontendResult"
            );
            PausedTaskRunRef secondBackendRequeried = fixture.waitingForOutput(
                secondId,
                "backendResult"
            );
            Execution firstAtSecurity = resume(
                fixture,
                firstFrontendRequeried,
                Map.of("frontendResult", "FIRST-FRONTEND")
            );
            Execution secondAtSecurity = resume(
                fixture,
                secondBackendRequeried,
                Map.of("backendResult", "SECOND-BACKEND")
            );
            assertEquals(State.Type.PAUSED, firstAtSecurity.state().current());
            assertEquals(State.Type.PAUSED, secondAtSecurity.state().current());

            fixture.restartServer();
            persisted = persistedFlow(fixture, flow);
            PausedTaskRunRef firstSecurity = fixture.waitingForOutput(
                firstId,
                "securityDecision"
            );
            PausedTaskRunRef secondSecurity = fixture.waitingForOutput(
                secondId,
                "securityDecision"
            );
            Execution firstCompleted = resume(
                fixture,
                firstSecurity,
                Map.of("securityDecision", "FIRST-APPROVED")
            );
            Execution secondCompleted = resume(
                fixture,
                secondSecurity,
                Map.of("securityDecision", "SECOND-APPROVED")
            );

            assertEquals(State.Type.SUCCESS, firstCompleted.state().current());
            assertEquals(State.Type.SUCCESS, secondCompleted.state().current());
            assertEquals(
                taskKeys(firstCompleted, persisted),
                taskKeys(secondCompleted, persisted)
            );
            assertAllTasksRunOnce(firstCompleted, persisted);
            assertAllTasksRunOnce(secondCompleted, persisted);
            assertEquals(
                Map.of("backendResult", "FIRST-BACKEND"),
                run(firstCompleted, persisted, "backend-review").outputs()
            );
            assertEquals(
                Map.of("frontendResult", "FIRST-FRONTEND"),
                run(firstCompleted, persisted, "frontend-review").outputs()
            );
            assertEquals(
                Map.of("backendResult", "SECOND-BACKEND"),
                run(secondCompleted, persisted, "backend-review").outputs()
            );
            assertEquals(
                Map.of("frontendResult", "SECOND-FRONTEND"),
                run(secondCompleted, persisted, "frontend-review").outputs()
            );
            assertEquals(
                1,
                countRuns(firstCompleted, persisted, "summarize-reviews")
            );
            assertEquals(
                1,
                countRuns(secondCompleted, persisted, "summarize-reviews")
            );
            assertParentTask(
                firstCompleted,
                persisted,
                "backend-review",
                "review-stage"
            );
            assertParentTask(
                secondCompleted,
                persisted,
                "frontend-review",
                "review-stage"
            );
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s6MovedBackendPauseUsesTheNewDirectHierarchy() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(movedBackendYaml("uc07-s6-flow"));
            Execution started = fixture.startAndAwait(flow);
            String executionId = started.id();

            fixture.restartServer();
            Flow persisted = persistedFlow(fixture, flow);
            assertDefinitionParent(persisted, "backend-review", "review-stage");
            assertDefinitionParent(persisted, "frontend-review", "frontend-build");
            PausedTaskRunRef backend = fixture.waitingForOutput(
                executionId,
                "backendResult"
            );
            PausedTaskRunRef frontend = fixture.waitingForOutput(
                executionId,
                "frontendResult"
            );
            Execution afterBackend = resume(
                fixture,
                backend,
                Map.of("backendResult", "PASS")
            );
            assertParentTask(afterBackend, persisted, "backend-review", "review-stage");
            assertNoRun(afterBackend, persisted, "summarize-reviews");

            fixture.restartServer();
            persisted = persistedFlow(fixture, flow);
            PausedTaskRunRef requeriedFrontend = fixture.waitingForOutput(
                executionId,
                "frontendResult"
            );
            assertEquals(frontend.taskRunId(), requeriedFrontend.taskRunId());
            resume(
                fixture,
                requeriedFrontend,
                Map.of("frontendResult", "PASS")
            );

            fixture.restartServer();
            persisted = persistedFlow(fixture, flow);
            PausedTaskRunRef security = fixture.waitingForOutput(
                executionId,
                "securityDecision"
            );
            Execution completed = resume(
                fixture,
                security,
                Map.of("securityDecision", "APPROVED")
            );

            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertParentTask(completed, persisted, "backend-review", "review-stage");
            assertParentTask(completed, persisted, "frontend-review", "frontend-build");
            assertAllTasksRunOnce(completed, persisted);
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s7WithoutParallelNodeSiblingPauseAndFinishRemainSerial() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(serialYaml("uc07-s7-flow"));
            Execution started = fixture.startAndAwait(flow);
            String executionId = started.id();

            fixture.restartServer();
            Flow persisted = persistedFlow(fixture, flow);
            Execution waiting = query(fixture, executionId);
            assertEquals(
                List.of("normal-stage", "confirm", "create-confirm"),
                taskKeys(waiting, persisted)
            );
            assertEquals(
                Set.of("confirm"),
                pausedKeys(fixture, persisted, executionId)
            );
            assertNoRun(waiting, persisted, "finish-log");

            PausedTaskRunRef confirm = fixture.waitingForOutput(
                executionId,
                "decision"
            );
            Execution completed = resume(
                fixture,
                confirm,
                Map.of("decision", "CONFIRMED")
            );
            assertEquals(
                List.of(
                    "normal-stage",
                    "confirm",
                    "create-confirm",
                    "finish-log"
                ),
                taskKeys(completed, persisted)
            );
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(
                Map.of("decision", "CONFIRMED"),
                run(completed, persisted, "confirm").outputs()
            );
            assertEquals(
                State.Type.SUCCESS,
                run(completed, persisted, "finish-log").state().current()
            );
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    private static Execution resume(
        WorkflowUcFixture fixture,
        PausedTaskRunRef pausedTaskRun,
        Map<String, ?> outputs
    ) {
        Execution accepted = fixture.executionService().resume(
            fixture.session(),
            pausedTaskRun.executionId(),
            pausedTaskRun.taskRunId(),
            outputs
        );
        fixture.awaitExecution(
            fixture.session(),
            accepted.id(),
            execution -> execution.isTerminal()
                || !execution.requireTaskRun(pausedTaskRun.taskRunId())
                    .state()
                    .is(State.Type.PAUSED)
        );
        return fixture.awaitStable(accepted.id());
    }

    private static Execution query(
        WorkflowUcFixture fixture,
        String executionId
    ) {
        return fixture.executionService().execution(
            fixture.session(),
            executionId
        ).orElseThrow();
    }

    private static Flow persistedFlow(
        WorkflowUcFixture fixture,
        Flow deployed
    ) {
        return fixture.flowService().flow(
            fixture.session(),
            deployed.key(),
            deployed.reversion()
        ).orElseThrow();
    }

    private static Set<String> pausedKeys(
        WorkflowUcFixture fixture,
        Flow flow,
        String executionId
    ) {
        Execution execution = query(fixture, executionId);
        Set<String> keys = new HashSet<>();
        fixture.pausedTaskRuns().stream()
            .filter(paused -> paused.executionId().equals(executionId))
            .map(paused -> execution.requireTaskRun(paused.taskRunId()).taskId())
            .map(taskId -> taskById(flow, taskId))
            .filter(Pause.class::isInstance)
            .map(Task::key)
            .forEach(keys::add);
        return Set.copyOf(keys);
    }

    private static List<String> taskKeys(Execution execution, Flow flow) {
        return execution.taskRuns().stream()
            .map(taskRun -> taskById(flow, taskRun.taskId()).key())
            .toList();
    }

    private static TaskRun run(
        Execution execution,
        Flow flow,
        String key
    ) {
        return execution.taskRunsForTask(task(flow, key).id()).stream()
            .findFirst()
            .orElseThrow();
    }

    private static long countRuns(
        Execution execution,
        Flow flow,
        String key
    ) {
        return execution.taskRunsForTask(task(flow, key).id()).size();
    }

    private static void assertNoRun(
        Execution execution,
        Flow flow,
        String key
    ) {
        assertEquals(0, countRuns(execution, flow, key));
    }

    private static void assertAllTasksRunOnce(
        Execution execution,
        Flow flow
    ) {
        assertEquals(flow.allTasks().size(), execution.taskRuns().size());
        flow.allTasks().forEach(candidate -> assertEquals(
            1,
            execution.taskRunsForTask(candidate.id()).size(),
            () -> "Task did not run once: " + candidate.key()
        ));
        assertTrue(execution.taskRuns().stream().allMatch(taskRun ->
            taskRun.state().is(State.Type.SUCCESS)
        ));
    }

    private static void assertParentTask(
        Execution execution,
        Flow flow,
        String childKey,
        String parentKey
    ) {
        assertEquals(
            run(execution, flow, parentKey).id(),
            run(execution, flow, childKey).parentId().orElseThrow()
        );
    }

    private static void assertDefinitionParent(
        Flow flow,
        String childKey,
        String parentKey
    ) {
        assertEquals(
            parentKey,
            definitionParentKey(flow.tasks(), childKey, null)
        );
    }

    private static String definitionParentKey(
        List<Task> tasks,
        String targetKey,
        String parentKey
    ) {
        for (Task candidate : tasks) {
            if (candidate.key().equals(targetKey)) {
                return parentKey;
            }
            String found = definitionParentKey(
                candidate.definitionChildren(),
                targetKey,
                candidate.key()
            );
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Task task(Flow flow, String key) {
        return flow.allTasks().stream()
            .filter(candidate -> candidate.key().equals(key))
            .findFirst()
            .orElseThrow();
    }

    private static Task taskById(Flow flow, String taskId) {
        return flow.allTasks().stream()
            .filter(candidate -> candidate.id().equals(taskId))
            .findFirst()
            .orElseThrow();
    }

    private static void assertUnchanged(
        Execution expected,
        Execution actual
    ) {
        assertEquals(expected.state(), actual.state());
        assertEquals(expected.taskRuns().size(), actual.taskRuns().size());
        for (int index = 0; index < expected.taskRuns().size(); index++) {
            TaskRun expectedRun = expected.taskRuns().get(index);
            TaskRun actualRun = actual.taskRuns().get(index);
            assertEquals(expectedRun.id(), actualRun.id());
            assertEquals(expectedRun.taskId(), actualRun.taskId());
            assertEquals(expectedRun.parentId(), actualRun.parentId());
            assertEquals(expectedRun.state(), actualRun.state());
            assertEquals(expectedRun.inputs(), actualRun.inputs());
            assertEquals(expectedRun.outputs(), actualRun.outputs());
            assertEquals(expectedRun.error(), actualRun.error());
        }
    }

    private static String duplicatePauseKeyYaml(String key) {
        return standardYaml(key).replace(
            "key: security-approve",
            "key: backend-review"
        );
    }

    private static String standardYaml(String key) {
        return """
            key: %s
            description: multi-stage Pause Flow
            tasks:
              - key: prepare
                type: org.cses.flow.extensions.log.Log
                message: "test step"
              - key: review-stage
                type: org.cses.flow.extensions.flow.Parallel
                tasks:
                  - key: backend-review
                    type: org.cses.flow.extensions.flow.Pause
                    onPause:
                      key: create-backend-review
                      type: org.cses.flow.extensions.log.Log
                      message: "test step"
                    onResume:
                      - key: backendResult
                        type: STRING
                    outputs:
                      - key: backendResult
                        type: STRING
                  - key: frontend-review
                    type: org.cses.flow.extensions.flow.Pause
                    onPause:
                      key: create-frontend-review
                      type: org.cses.flow.extensions.log.Log
                      message: "test step"
                    onResume:
                      - key: frontendResult
                        type: STRING
                    outputs:
                      - key: frontendResult
                        type: STRING
              - key: summarize-reviews
                type: org.cses.flow.extensions.log.Log
                message: "test step"
              - key: security-stage
                type: org.cses.flow.extensions.flow.Sequence
                tasks:
                  - key: security-approve
                    type: org.cses.flow.extensions.flow.Pause
                    onPause:
                      key: create-security-approval
                      type: org.cses.flow.extensions.log.Log
                      message: "test step"
                    onResume:
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

    private static String movedBackendYaml(String key) {
        return """
            key: %s
            description: moved backend Pause hierarchy
            tasks:
              - key: prepare
                type: org.cses.flow.extensions.log.Log
                message: "test step"
              - key: review-stage
                type: org.cses.flow.extensions.flow.Parallel
                tasks:
                  - key: backend-build
                    type: org.cses.flow.extensions.log.Log
                    message: "test step"
                  - key: backend-review
                    type: org.cses.flow.extensions.flow.Pause
                    onPause:
                      key: create-backend-review
                      type: org.cses.flow.extensions.log.Log
                      message: "test step"
                    onResume:
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
                        onPause:
                          key: create-frontend-review
                          type: org.cses.flow.extensions.log.Log
                          message: "test step"
                        onResume:
                          - key: frontendResult
                            type: STRING
                        outputs:
                          - key: frontendResult
                            type: STRING
              - key: summarize-reviews
                type: org.cses.flow.extensions.log.Log
                message: "test step"
              - key: security-stage
                type: org.cses.flow.extensions.flow.Sequence
                tasks:
                  - key: security-approve
                    type: org.cses.flow.extensions.flow.Pause
                    onPause:
                      key: create-security-approval
                      type: org.cses.flow.extensions.log.Log
                      message: "test step"
                    onResume:
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

    private static String serialYaml(String key) {
        return """
            key: %s
            description: serial Pause without an explicit parallel node
            tasks:
              - key: normal-stage
                type: org.cses.flow.extensions.flow.Sequence
                tasks:
                  - key: confirm
                    type: org.cses.flow.extensions.flow.Pause
                    onPause:
                      key: create-confirm
                      type: org.cses.flow.extensions.log.Log
                      message: "test step"
                    onResume:
                      - key: decision
                        type: STRING
                    outputs:
                      - key: decision
                        type: STRING
                  - key: finish-log
                    type: org.cses.flow.extensions.log.Log
                    message: "test step"
            """.formatted(key);
    }
}
