package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.cses.flow.core.services.executions.WorkflowUcFixture.PausedTaskRunRef;

/**
 * UC: docs/uc/flow/UC-10 用户处理包含嵌套编排的审批 Flow.md
 */
class Uc10NestedApprovalFlowTest {

    @Test
    void s1SequentialNestedApprovalsCompleteOnlyAfterAllApprovals() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(serialApprovalYaml("uc10-s1-flow"));
            Execution started = fixture.startAndAwait(flow);
            String executionId = started.id();

            fixture.restartServer();
            Flow persisted = deployedFlow(fixture, flow);
            Execution initial = query(fixture, executionId);
            PausedTaskRunRef initialApproval = pausedTask(
                initial,
                persisted,
                "initial-approval"
            );
            assertNoRun(initial, persisted, "finance-approval");
            assertNoRun(initial, persisted, "legal-approval");
            assertNoRun(initial, persisted, "record-approved");
            assertNoRun(initial, persisted, "finish");

            Execution afterInitial = fixture.resume(
                initialApproval,
                Map.of("decision", "APPROVED")
            );
            assertEquals(State.Type.PAUSED, afterInitial.state().current());
            assertEquals(
                State.Type.PAUSED,
                onlyRun(afterInitial, persisted, "finance-approval")
                    .state().current()
            );
            assertNoRun(afterInitial, persisted, "legal-approval");
            assertNoRun(afterInitial, persisted, "record-approved");
            assertNoRun(afterInitial, persisted, "finish");

            fixture.restartServer();
            persisted = deployedFlow(fixture, flow);
            Execution financeWaiting = query(fixture, executionId);
            PausedTaskRunRef financeApproval = pausedTask(
                financeWaiting,
                persisted,
                "finance-approval"
            );
            Execution afterFinance = fixture.resume(
                financeApproval,
                Map.of("decision", "APPROVED")
            );
            assertEquals(State.Type.PAUSED, afterFinance.state().current());
            assertEquals(
                State.Type.PAUSED,
                onlyRun(afterFinance, persisted, "legal-approval")
                    .state().current()
            );
            assertNoRun(afterFinance, persisted, "record-approved");
            assertNoRun(afterFinance, persisted, "finish");

            fixture.restartServer();
            persisted = deployedFlow(fixture, flow);
            Execution legalWaiting = query(fixture, executionId);
            PausedTaskRunRef legalApproval = pausedTask(
                legalWaiting,
                persisted,
                "legal-approval"
            );
            Execution completed = fixture.resume(
                legalApproval,
                Map.of("decision", "APPROVED")
            );

            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(
                List.of(
                    "review-stage",
                    "initial-approval",
                    "create-initial-approval",
                    "approved-route",
                    "nested-approvals",
                    "finance-approval",
                    "create-finance-approval",
                    "legal-approval",
                    "create-legal-approval",
                    "record-approved",
                    "rejected-route",
                    "finish"
                ),
                taskKeys(completed, persisted)
            );
            assertEquals(1, countRuns(completed, persisted, "finance-approval"));
            assertEquals(1, countRuns(completed, persisted, "legal-approval"));
            assertEquals(1, countRuns(completed, persisted, "record-approved"));
            assertEquals(1, countRuns(completed, persisted, "finish"));
            assertEquals(
                Map.of("decision", "APPROVED"),
                onlyRun(completed, persisted, "initial-approval").outputs()
            );
            assertTrue(completed.unfinishedTaskRuns().isEmpty());
            assertNoPendingWork(fixture, executionId);
        }
    }

    @Test
    void s2RejectedInitialApprovalShortCircuitsNestedApprovalBranch() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(serialApprovalYaml("uc10-s2-flow"));
            Execution started = fixture.startAndAwait(flow);
            String executionId = started.id();

            fixture.restartServer();
            Flow persisted = deployedFlow(fixture, flow);
            Execution waiting = query(fixture, executionId);
            Execution completed = fixture.resume(
                pausedTask(waiting, persisted, "initial-approval"),
                Map.of("decision", "REJECTED")
            );

            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(
                List.of(
                    "review-stage",
                    "initial-approval",
                    "create-initial-approval",
                    "approved-route",
                    "rejected-route",
                    "record-rejected",
                    "finish"
                ),
                taskKeys(completed, persisted)
            );
            assertEquals(
                Map.of("decision", "REJECTED"),
                onlyRun(completed, persisted, "initial-approval").outputs()
            );
            assertNoRun(completed, persisted, "nested-approvals");
            assertNoRun(completed, persisted, "finance-approval");
            assertNoRun(completed, persisted, "legal-approval");
            assertNoRun(completed, persisted, "record-approved");
            assertEquals(1, countRuns(completed, persisted, "record-rejected"));
            assertEquals(1, countRuns(completed, persisted, "finish"));
            assertTrue(completed.unfinishedTaskRuns().isEmpty());
            assertNoPendingWork(fixture, executionId);
        }
    }

    @Test
    void s3ParallelNestedApprovalsJoinBeforeFinalApproval() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(parallelApprovalYaml("uc10-s3-flow"));
            Execution started = fixture.startAndAwait(flow);
            String executionId = started.id();

            fixture.restartServer();
            Flow persisted = deployedFlow(fixture, flow);
            Execution initial = query(fixture, executionId);
            PausedTaskRunRef firstOwner = pausedTask(
                initial,
                persisted,
                "first-owner"
            );
            PausedTaskRunRef secondOwner = pausedTask(
                initial,
                persisted,
                "second-owner"
            );
            assertNotEquals(firstOwner.taskRunId(), secondOwner.taskRunId());
            assertNoRun(initial, persisted, "first-reviewer");
            assertNoRun(initial, persisted, "second-reviewer");
            assertNoRun(initial, persisted, "final-approval");
            assertNoRun(initial, persisted, "summarize");
            assertNoRun(initial, persisted, "finish");

            Execution afterFirstOwner = fixture.resume(
                firstOwner,
                Map.of("decision", "APPROVED")
            );
            assertEquals(State.Type.PAUSED, afterFirstOwner.state().current());
            assertEquals(
                State.Type.PAUSED,
                onlyRun(afterFirstOwner, persisted, "first-reviewer")
                    .state().current()
            );
            assertEquals(
                State.Type.PAUSED,
                onlyRun(afterFirstOwner, persisted, "second-owner")
                    .state().current()
            );
            assertNoRun(afterFirstOwner, persisted, "second-reviewer");
            assertNoRun(afterFirstOwner, persisted, "final-approval");
            assertNoRun(afterFirstOwner, persisted, "summarize");

            fixture.restartServer();
            persisted = deployedFlow(fixture, flow);
            Execution firstReviewWaiting = query(fixture, executionId);
            Execution afterFirstReview = fixture.resume(
                pausedTask(firstReviewWaiting, persisted, "first-reviewer"),
                Map.of("decision", "APPROVED")
            );
            assertEquals(State.Type.PAUSED, afterFirstReview.state().current());
            assertEquals(
                State.Type.PAUSED,
                onlyRun(afterFirstReview, persisted, "second-owner")
                    .state().current()
            );
            assertNoRun(afterFirstReview, persisted, "final-approval");
            assertNoRun(afterFirstReview, persisted, "summarize");

            fixture.restartServer();
            persisted = deployedFlow(fixture, flow);
            Execution secondOwnerWaiting = query(fixture, executionId);
            Execution afterSecondOwner = fixture.resume(
                pausedTask(secondOwnerWaiting, persisted, "second-owner"),
                Map.of("decision", "APPROVED")
            );
            assertEquals(State.Type.PAUSED, afterSecondOwner.state().current());
            assertEquals(
                State.Type.PAUSED,
                onlyRun(afterSecondOwner, persisted, "second-reviewer")
                    .state().current()
            );
            assertNoRun(afterSecondOwner, persisted, "final-approval");
            assertNoRun(afterSecondOwner, persisted, "summarize");

            fixture.restartServer();
            persisted = deployedFlow(fixture, flow);
            Execution secondReviewWaiting = query(fixture, executionId);
            Execution beforeFinal = fixture.resume(
                pausedTask(secondReviewWaiting, persisted, "second-reviewer"),
                Map.of("decision", "APPROVED")
            );
            assertEquals(State.Type.PAUSED, beforeFinal.state().current());
            assertEquals(
                State.Type.PAUSED,
                onlyRun(beforeFinal, persisted, "final-approval").state().current()
            );
            assertEquals(1, countRuns(beforeFinal, persisted, "first-owner"));
            assertEquals(1, countRuns(beforeFinal, persisted, "first-reviewer"));
            assertEquals(1, countRuns(beforeFinal, persisted, "second-owner"));
            assertEquals(1, countRuns(beforeFinal, persisted, "second-reviewer"));
            assertNoRun(beforeFinal, persisted, "summarize");
            assertNoRun(beforeFinal, persisted, "finish");

            fixture.restartServer();
            persisted = deployedFlow(fixture, flow);
            Execution finalWaiting = query(fixture, executionId);
            Execution completed = fixture.resume(
                pausedTask(finalWaiting, persisted, "final-approval"),
                Map.of("decision", "APPROVED")
            );
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(1, countRuns(completed, persisted, "final-approval"));
            assertEquals(1, countRuns(completed, persisted, "summarize"));
            assertEquals(1, countRuns(completed, persisted, "finish"));
            assertTrue(completed.unfinishedTaskRuns().isEmpty());
            assertNoPendingWork(fixture, executionId);
        }
    }

    @Test
    void s4CancelingParallelNestedApprovalsPreventsRemainingAndFollowingSteps() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(parallelApprovalYaml("uc10-s4-flow"));
            Execution started = fixture.startAndAwait(flow);
            String executionId = started.id();

            fixture.restartServer();
            Flow persisted = deployedFlow(fixture, flow);
            Execution initial = query(fixture, executionId);
            PausedTaskRunRef firstOwner = pausedTask(
                initial,
                persisted,
                "first-owner"
            );
            PausedTaskRunRef secondOwner = pausedTask(
                initial,
                persisted,
                "second-owner"
            );
            Execution afterFirstOwner = fixture.resume(
                firstOwner,
                Map.of("decision", "APPROVED")
            );
            assertEquals(State.Type.PAUSED, afterFirstOwner.state().current());
            PausedTaskRunRef firstReviewer = pausedTask(
                afterFirstOwner,
                persisted,
                "first-reviewer"
            );
            assertEquals(
                State.Type.PAUSED,
                onlyRun(afterFirstOwner, persisted, "second-owner")
                    .state().current()
            );
            assertNoRun(afterFirstOwner, persisted, "final-approval");
            assertNoRun(afterFirstOwner, persisted, "summarize");
            assertNoRun(afterFirstOwner, persisted, "publish");
            assertNoRun(afterFirstOwner, persisted, "notify");

            Execution canceled = fixture.cancel(executionId);
            assertEquals(State.Type.KILLED, canceled.state().current());
            Execution reloaded = query(fixture, executionId);
            assertEquals(State.Type.KILLED, reloaded.state().current());
            assertEquals(
                State.Type.KILLED,
                onlyRun(reloaded, persisted, "first-reviewer").state().current()
            );
            assertEquals(
                State.Type.KILLED,
                onlyRun(reloaded, persisted, "second-owner").state().current()
            );
            assertNoRun(reloaded, persisted, "second-reviewer");
            assertNoRun(reloaded, persisted, "final-approval");
            assertNoRun(reloaded, persisted, "summarize");
            assertNoRun(reloaded, persisted, "publish");
            assertNoRun(reloaded, persisted, "notify");
            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().resume(
                    fixture.session(),
                    firstReviewer.executionId(),
                    firstReviewer.taskRunId(),
                    Map.of("decision", "APPROVED")
                )
            );
            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().resume(
                    fixture.session(),
                    secondOwner.executionId(),
                    secondOwner.taskRunId(),
                    Map.of("decision", "APPROVED")
                )
            );
            assertTrue(reloaded.unfinishedTaskRuns().isEmpty());
            assertNoPendingWork(fixture, executionId);
        }
    }

    @Test
    void s5ConditionalNestedReviewLoopReachesFinalApprovalAfterSupplement() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(loopApprovalYaml("uc10-s5-flow", 3));
            Execution started = fixture.startAndAwait(flow);
            String executionId = started.id();

            fixture.restartServer();
            Flow persisted = deployedFlow(fixture, flow);
            Execution firstMaterialWaiting = query(fixture, executionId);
            Execution firstReviewWaiting = fixture.resume(
                pausedTask(firstMaterialWaiting, persisted, "material-submission"),
                Map.of("decision", "SUBMITTED")
            );
            assertEquals(State.Type.PAUSED, firstReviewWaiting.state().current());
            assertEquals(
                State.Type.PAUSED,
                onlyRun(firstReviewWaiting, persisted, "material-review")
                    .state().current()
            );
            assertNoRun(firstReviewWaiting, persisted, "final-approval");

            fixture.restartServer();
            persisted = deployedFlow(fixture, flow);
            Execution firstReview = query(fixture, executionId);
            Execution secondMaterialWaiting = fixture.resume(
                pausedTask(firstReview, persisted, "material-review"),
                Map.of("decision", "NEEDS_MORE")
            );
            assertEquals(State.Type.PAUSED, secondMaterialWaiting.state().current());
            assertEquals(
                1,
                countRuns(secondMaterialWaiting, persisted, "material-review")
            );
            assertEquals(
                State.Type.PAUSED,
                latestRun(secondMaterialWaiting, persisted, "material-submission")
                    .state().current()
            );
            assertEquals(
                2,
                countRuns(secondMaterialWaiting, persisted, "material-submission")
            );
            assertNoRun(secondMaterialWaiting, persisted, "final-approval");

            fixture.restartServer();
            persisted = deployedFlow(fixture, flow);
            Execution secondMaterial = query(fixture, executionId);
            Execution secondReviewWaiting = fixture.resume(
                pausedTask(secondMaterial, persisted, "material-submission"),
                Map.of("decision", "SUBMITTED")
            );
            assertEquals(State.Type.PAUSED, secondReviewWaiting.state().current());
            assertEquals(
                2,
                countRuns(secondReviewWaiting, persisted, "material-review")
            );
            assertNoRun(secondReviewWaiting, persisted, "final-approval");

            fixture.restartServer();
            persisted = deployedFlow(fixture, flow);
            Execution secondReview = query(fixture, executionId);
            Execution finalApprovalWaiting = fixture.resume(
                pausedTask(secondReview, persisted, "material-review"),
                Map.of("decision", "APPROVED")
            );
            assertEquals(State.Type.PAUSED, finalApprovalWaiting.state().current());
            assertEquals(
                State.Type.PAUSED,
                onlyRun(finalApprovalWaiting, persisted, "final-approval")
                    .state().current()
            );
            assertEquals(2, countRuns(finalApprovalWaiting, persisted, "material-submission"));
            assertEquals(2, countRuns(finalApprovalWaiting, persisted, "material-review"));
            assertNoRun(finalApprovalWaiting, persisted, "approved-record");

            fixture.restartServer();
            persisted = deployedFlow(fixture, flow);
            Execution finalApproval = query(fixture, executionId);
            Execution completed = fixture.resume(
                pausedTask(finalApproval, persisted, "final-approval"),
                Map.of("decision", "APPROVED")
            );
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(2, countRuns(completed, persisted, "material-submission"));
            assertEquals(2, countRuns(completed, persisted, "material-review"));
            assertEquals(1, countRuns(completed, persisted, "final-approval"));
            assertEquals(1, countRuns(completed, persisted, "approved-record"));
            assertTrue(completed.unfinishedTaskRuns().isEmpty());
            assertNoPendingWork(fixture, executionId);
        }
    }

    @Test
    void s6ConditionalNestedReviewLoopFailsAtMaximumWithoutFinalApproval() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(loopApprovalYaml("uc10-s6-flow", 2));
            Execution started = fixture.startAndAwait(flow);
            String executionId = started.id();

            fixture.restartServer();
            Flow persisted = deployedFlow(fixture, flow);
            Execution firstMaterial = query(fixture, executionId);
            Execution firstReview = fixture.resume(
                pausedTask(firstMaterial, persisted, "material-submission"),
                Map.of("decision", "SUBMITTED")
            );
            assertEquals(State.Type.PAUSED, firstReview.state().current());

            fixture.restartServer();
            persisted = deployedFlow(fixture, flow);
            Execution firstReviewWaiting = query(fixture, executionId);
            Execution secondMaterial = fixture.resume(
                pausedTask(firstReviewWaiting, persisted, "material-review"),
                Map.of("decision", "NEEDS_MORE")
            );
            assertEquals(State.Type.PAUSED, secondMaterial.state().current());
            assertEquals(2, countRuns(secondMaterial, persisted, "material-submission"));
            assertEquals(1, countRuns(secondMaterial, persisted, "material-review"));
            assertNoRun(secondMaterial, persisted, "final-approval");

            fixture.restartServer();
            persisted = deployedFlow(fixture, flow);
            Execution secondMaterialWaiting = query(fixture, executionId);
            Execution secondReview = fixture.resume(
                pausedTask(secondMaterialWaiting, persisted, "material-submission"),
                Map.of("decision", "SUBMITTED")
            );
            assertEquals(State.Type.PAUSED, secondReview.state().current());

            fixture.restartServer();
            persisted = deployedFlow(fixture, flow);
            Execution secondReviewWaiting = query(fixture, executionId);
            Execution failed = fixture.resume(
                pausedTask(secondReviewWaiting, persisted, "material-review"),
                Map.of("decision", "NEEDS_MORE")
            );

            assertEquals(State.Type.FAILED, failed.state().current());
            assertEquals(2, countRuns(failed, persisted, "material-submission"));
            assertEquals(2, countRuns(failed, persisted, "material-review"));
            assertEquals(
                State.Type.FAILED,
                onlyRun(failed, persisted, "review-loop").state().current()
            );
            assertNoRun(failed, persisted, "final-approval");
            assertNoRun(failed, persisted, "approved-record");
            assertTrue(failed.unfinishedTaskRuns().isEmpty());
            assertNoPendingWork(fixture, executionId);
        }
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

    private static Flow deployedFlow(
        WorkflowUcFixture fixture,
        Flow expected
    ) {
        return fixture.flowService().flow(
            fixture.session(),
            expected.key(),
            expected.version()
        ).orElseThrow();
    }

    private static PausedTaskRunRef pausedTask(
        Execution execution,
        Flow flow,
        String taskKey
    ) {
        Task task = task(flow, taskKey);
        TaskRun run = execution.taskRunsForTask(task.id()).stream()
            .filter(TaskRun::isPaused)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Expected paused task: " + taskKey
            ));
        return PausedTaskRunRef.from(execution.id(), run.id());
    }

    private static Task task(Flow flow, String key) {
        return flow.allTasks().stream()
            .filter(candidate -> candidate.key().equals(key))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing task: " + key));
    }

    private static TaskRun onlyRun(
        Execution execution,
        Flow flow,
        String key
    ) {
        List<TaskRun> runs = runs(execution, flow, key);
        assertEquals(1, runs.size(), "Expected one run for " + key);
        return runs.getFirst();
    }

    private static TaskRun latestRun(
        Execution execution,
        Flow flow,
        String key
    ) {
        List<TaskRun> runs = runs(execution, flow, key);
        assertFalse(runs.isEmpty(), "Expected a run for " + key);
        return runs.getLast();
    }

    private static List<TaskRun> runs(
        Execution execution,
        Flow flow,
        String key
    ) {
        return execution.taskRunsForTask(task(flow, key).id());
    }

    private static long countRuns(
        Execution execution,
        Flow flow,
        String key
    ) {
        return runs(execution, flow, key).size();
    }

    private static void assertNoRun(
        Execution execution,
        Flow flow,
        String key
    ) {
        assertEquals(0, countRuns(execution, flow, key),
            "Expected no run for " + key);
    }

    private static List<String> taskKeys(Execution execution, Flow flow) {
        return execution.taskRuns().stream()
            .map(run -> flow.findTask(run.taskId()).orElseThrow().key())
            .toList();
    }

    private static void assertNoPendingWork(
        WorkflowUcFixture fixture,
        String executionId
    ) {
        Execution execution = query(fixture, executionId);
        assertTrue(execution.isTerminal());
        assertTrue(execution.unfinishedTaskRuns().isEmpty());
        assertTrue(execution.pausedTaskRuns().isEmpty());
    }

    private static String serialApprovalYaml(String key) {
        return """
            key: %s
            description: nested serial approval
            tasks:
              - key: review-stage
                type: org.cses.flow.extensions.flow.Sequence
                tasks:
                  - key: initial-approval
                    type: org.cses.flow.extensions.flow.Pause
                    pause:
                      key: create-initial-approval
                      type: org.cses.flow.extensions.log.Log
                      message: "test step"
                    resume:
                      - key: decision
                        type: STRING
                    outputs:
                      - key: decision
                        type: STRING
                  - key: approved-route
                    type: org.cses.flow.extensions.flow.Route
                    route: '{{ outputs.initial-approval.decision }} == APPROVED'
                    tasks:
                      - key: nested-approvals
                        type: org.cses.flow.extensions.flow.Sequence
                        tasks:
                          - key: finance-approval
                            type: org.cses.flow.extensions.flow.Pause
                            pause:
                              key: create-finance-approval
                              type: org.cses.flow.extensions.log.Log
                              message: "test step"
                            resume:
                              - key: decision
                                type: STRING
                            outputs:
                              - key: decision
                                type: STRING
                          - key: legal-approval
                            type: org.cses.flow.extensions.flow.Pause
                            pause:
                              key: create-legal-approval
                              type: org.cses.flow.extensions.log.Log
                              message: "test step"
                            resume:
                              - key: decision
                                type: STRING
                            outputs:
                              - key: decision
                                type: STRING
                      - key: record-approved
                        type: org.cses.flow.extensions.log.Log
                        message: "test step"
                  - key: rejected-route
                    type: org.cses.flow.extensions.flow.Route
                    route: '{{ outputs.initial-approval.decision }} == REJECTED'
                    tasks:
                      - key: record-rejected
                        type: org.cses.flow.extensions.log.Log
                        message: "test step"
              - key: finish
                type: org.cses.flow.extensions.log.Log
                message: "test step"
            """.formatted(key);
    }

    private static String parallelApprovalYaml(String key) {
        return """
            key: %s
            description: nested parallel approval
            tasks:
              - key: parallel-approvals
                type: org.cses.flow.extensions.flow.Parallel
                tasks:
                  - key: first-approval-chain
                    type: org.cses.flow.extensions.flow.Sequence
                    tasks:
                      - key: first-owner
                        type: org.cses.flow.extensions.flow.Pause
                        pause:
                          key: create-first-owner
                          type: org.cses.flow.extensions.log.Log
                          message: "test step"
                        resume:
                          - key: decision
                            type: STRING
                        outputs:
                          - key: decision
                            type: STRING
                      - key: first-reviewer
                        type: org.cses.flow.extensions.flow.Pause
                        pause:
                          key: create-first-reviewer
                          type: org.cses.flow.extensions.log.Log
                          message: "test step"
                        resume:
                          - key: decision
                            type: STRING
                        outputs:
                          - key: decision
                            type: STRING
                  - key: second-approval-chain
                    type: org.cses.flow.extensions.flow.Sequence
                    tasks:
                      - key: second-owner
                        type: org.cses.flow.extensions.flow.Pause
                        pause:
                          key: create-second-owner
                          type: org.cses.flow.extensions.log.Log
                          message: "test step"
                        resume:
                          - key: decision
                            type: STRING
                        outputs:
                          - key: decision
                            type: STRING
                      - key: second-reviewer
                        type: org.cses.flow.extensions.flow.Pause
                        pause:
                          key: create-second-reviewer
                          type: org.cses.flow.extensions.log.Log
                          message: "test step"
                        resume:
                          - key: decision
                            type: STRING
                        outputs:
                          - key: decision
                            type: STRING
              - key: final-approval
                type: org.cses.flow.extensions.flow.Pause
                pause:
                  key: create-final-approval
                  type: org.cses.flow.extensions.log.Log
                  message: "test step"
                resume:
                  - key: decision
                    type: STRING
                outputs:
                  - key: decision
                    type: STRING
              - key: summarize
                type: org.cses.flow.extensions.log.Log
                message: "test step"
              - key: publish
                type: org.cses.flow.extensions.log.Log
                message: "test step"
              - key: notify
                type: org.cses.flow.extensions.log.Log
                message: "test step"
              - key: finish
                type: org.cses.flow.extensions.log.Log
                message: "test step"
            """.formatted(key);
    }

    private static String loopApprovalYaml(String key, int maxIterations) {
        return """
            key: %s
            description: nested conditional approval review
            tasks:
              - key: review-loop
                type: org.cses.flow.extensions.flow.LoopUntil
                condition: '{{ outputs.material-review.decision }} == APPROVED'
                maxIterations: %d
                tasks:
                  - key: review-round
                    type: org.cses.flow.extensions.flow.Sequence
                    tasks:
                      - key: material-submission
                        type: org.cses.flow.extensions.flow.Pause
                        pause:
                          key: create-material-submission
                          type: org.cses.flow.extensions.log.Log
                          message: "test step"
                        resume:
                          - key: decision
                            type: STRING
                        outputs:
                          - key: decision
                            type: STRING
                      - key: material-review
                        type: org.cses.flow.extensions.flow.Pause
                        pause:
                          key: create-material-review
                          type: org.cses.flow.extensions.log.Log
                          message: "test step"
                        resume:
                          - key: decision
                            type: STRING
                        outputs:
                          - key: decision
                            type: STRING
              - key: final-approval
                type: org.cses.flow.extensions.flow.Pause
                pause:
                  key: create-final-approval
                  type: org.cses.flow.extensions.log.Log
                  message: "test step"
                resume:
                  - key: decision
                    type: STRING
                outputs:
                  - key: decision
                    type: STRING
              - key: approved-record
                type: org.cses.flow.extensions.log.Log
                message: "test step"
            """.formatted(key, maxIterations);
    }
}
