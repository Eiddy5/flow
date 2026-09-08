package org.cses.flow.core.services.executions;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.extensions.flow.Pause;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** Tests same-route rewind through a notification and rejects the old source. */
    @Test
    void s7SameConditionalBranchRewindsAndRejectsOldSource() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(rewindYaml("uc10-s7-flow",
                logYaml("before")
                    + routeYaml("high", "{{ inputs.amount }} > 400",
                        approvalYaml("first") + logYaml("notify") + approvalYaml("second"))
                    + routeYaml("low", "{{ inputs.amount }} <= 400", approvalYaml("unused"))
                    + recordYaml("record", "first", "second")));
            String id = startWithAmount(fixture, flow, 500).id();
            Execution waiting = approve(fixture, flow, id, "first", "OLD");
            TaskRun target = onlyRun(waiting, flow, "first");
            PausedTaskRunRef source = pausedTask(query(fixture, id), flow, "second");
            var attempt = fixture.rewind(source, target.id(), "same-branch");
            Execution rewound = query(fixture, id);
            assertPausedKeys(rewound, flow, "first");
            assertNotEquals(target.id(), latestRun(rewound, flow, "first").id());
            assertTrue(attempt.accepted().affectedTaskRunIds().contains(source.taskRunId()));
            assertTrue(attempt.accepted().affectedTaskRunIds().contains(target.id()));
            assertFalse(attempt.accepted().affectedTaskRunIds().contains(
                onlyRun(rewound, flow, "before").id()));
            assertNoRun(rewound, flow, "record");
            assertRejectedWithoutChange(fixture, id, () -> fixture.executionService().resume(
                fixture.session(), id, source.taskRunId(), Map.of("decision", "OLD")));
            assertRejectedWithoutChange(fixture, id, () -> fixture.executionService().rewind(
                fixture.session(), id, source.taskRunId(), target.id(), "stale-source"));
            Execution next = approve(fixture, flow, id, "first", "NEW");
            assertPausedKeys(next, flow, "second");
            assertNoRun(next, flow, "record");
            approve(fixture, flow, id, "second", "APPROVED");
            Execution completed = query(fixture, id);
            assertEquals(1, countRuns(completed, flow, "before"));
            assertEquals(2, countRuns(completed, flow, "first"));
            assertEquals(2, countRuns(completed, flow, "notify"));
            assertEquals(2, countRuns(completed, flow, "second"));
            assertNoRun(completed, flow, "unused");
            assertObservedDecision(completed, flow, "record", "first", "NEW");
            assertObservedDecision(completed, flow, "record", "second", "APPROVED");
            assertCompletedRewinds(fixture, flow, id, "same-branch");
        }
    }

    /** Tests rewind from the first conditional approval to an approval before the split. */
    @Test
    void s8FirstConditionalApprovalRewindsBeforeTheSplit() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(rewindYaml("uc10-s8-flow",
                logYaml("before") + approvalYaml("first") + logYaml("notify")
                    + routeYaml("high", "{{ inputs.amount }} > 400", approvalYaml("second"))
                    + routeYaml("low", "{{ inputs.amount }} <= 400", approvalYaml("unused"))
                    + recordYaml("record", "first", "second")));
            String id = startWithAmount(fixture, flow, 500).id();
            Execution waiting = approve(fixture, flow, id, "first", "OLD");
            fixture.rewind(pausedTask(query(fixture, id), flow, "second"),
                onlyRun(waiting, flow, "first").id(), "before-split");
            Execution rewound = query(fixture, id);
            assertPausedKeys(rewound, flow, "first");
            assertEquals(1, countRuns(rewound, flow, "before"));
            assertNoRun(rewound, flow, "record");
            Execution next = approve(fixture, flow, id, "first", "NEW");
            assertPausedKeys(next, flow, "second");
            assertNoRun(next, flow, "record");
            approve(fixture, flow, id, "second", "APPROVED");
            Execution completed = query(fixture, id);
            assertEquals(1, countRuns(completed, flow, "before"));
            assertEquals(2, countRuns(completed, flow, "first"));
            assertEquals(2, countRuns(completed, flow, "notify"));
            assertEquals(2, countRuns(completed, flow, "second"));
            assertNoRun(completed, flow, "unused");
            assertObservedDecision(completed, flow, "record", "first", "NEW");
            assertObservedDecision(completed, flow, "record", "second", "APPROVED");
            assertCompletedRewinds(fixture, flow, id, "before-split");
        }
    }

    /** Tests both amount paths independently when rewinding from after their merge. */
    @Test
    void s9AfterConditionalMergeRewindsOnlyTheActuallyExecutedBranch() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(rewindYaml("uc10-s9-flow",
                logYaml("before")
                    + routeYaml("high", "{{ inputs.amount }} > 400", approvalYaml("high-review"))
                    + routeYaml("low", "{{ inputs.amount }} <= 400", approvalYaml("low-review"))
                    + logYaml("notify") + approvalYaml("final", "high-review", "low-review")
                    + recordYaml("record", "high-review", "low-review")));
            List<Execution> started = List.of(startWithAmount(fixture, flow, 500),
                startWithAmount(fixture, flow, 100));
            List<String> branches = List.of("high-review", "low-review");
            for (int index = 0; index < started.size(); index++) {
                String id = started.get(index).id();
                String branch = branches.get(index);
                String unmatched = branches.get(1 - index);
                Execution waiting = approve(fixture, flow, id, branch, "OLD");
                assertNoRun(waiting, flow, unmatched);
                assertEquals(List.of(onlyRun(waiting, flow, branch).id()),
                    fixture.executionService().previousCompletedTaskRuns(fixture.session(), id,
                        pausedTask(waiting, flow, "final").taskRunId(),
                        Set.of("high-review", "low-review", "final"))
                        .stream().map(TaskRun::id).toList());
                fixture.rewind(pausedTask(query(fixture, id), flow, "final"),
                    onlyRun(waiting, flow, branch).id(), "actual-branch");
                Execution rewound = query(fixture, id);
                assertPausedKeys(rewound, flow, branch);
                assertNoRun(rewound, flow, unmatched);
                assertEquals(1, countRuns(rewound, flow, "before"));
                assertNoRun(rewound, flow, "record");
                if (index == 0) {
                    assertEquals(started.get(1).pausedTaskRuns().stream().map(TaskRun::id).toList(),
                        query(fixture, started.get(1).id()).pausedTaskRuns().stream().map(TaskRun::id).toList());
                }
                Execution next = approve(fixture, flow, id, branch, "NEW");
                assertPausedKeys(next, flow, "final");
                assertObservedDecision(next, flow, "prepare-final", branch, "NEW");
                approve(fixture, flow, id, "final", "APPROVED");
                Execution completed = query(fixture, id);
                assertEquals(2, countRuns(completed, flow, branch));
                assertEquals(2, countRuns(completed, flow, "notify"));
                assertEquals(2, countRuns(completed, flow, "final"));
                assertEquals(1, countRuns(completed, flow, "before"));
                assertNoRun(completed, flow, unmatched);
                assertObservedDecision(completed, flow, "record", branch, "NEW");
                assertCompletedRewinds(fixture, flow, id, "actual-branch");
            }
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    /** Tests recursive rewind from four conditional levels to their outer approval. */
    @Test
    void s10DeepConditionalApprovalRewindsToOuterApproval() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            String nested = logYaml("notify") + approvalYaml("deep");
            for (int level = 4; level >= 2; level--) {
                nested = routeYaml("level-" + level, "{{ inputs.amount }} > 400", nested)
                    + routeYaml("skip-" + level, "{{ inputs.amount }} <= 400",
                        approvalYaml("unused-" + level));
            }
            Flow flow = fixture.deploy(rewindYaml("uc10-s10-flow", logYaml("before")
                + routeYaml("level-1", "{{ inputs.amount }} > 400",
                    approvalYaml("first") + nested + recordYaml("outer-record", "first", "deep"))
                + routeYaml("skip-1", "{{ inputs.amount }} <= 400", approvalYaml("unused-1"))
                + recordYaml("record", "first", "deep")));
            String id = startWithAmount(fixture, flow, 500).id();
            Execution waiting = approve(fixture, flow, id, "first", "OLD");
            fixture.rewind(pausedTask(query(fixture, id), flow, "deep"),
                onlyRun(waiting, flow, "first").id(), "deep-to-outer");
            Execution rewound = query(fixture, id);
            assertPausedKeys(rewound, flow, "first");
            assertEquals(1, countRuns(rewound, flow, "before"));
            assertNoRun(rewound, flow, "outer-record");
            assertNoRun(rewound, flow, "record");
            Execution next = approve(fixture, flow, id, "first", "NEW");
            assertPausedKeys(next, flow, "deep");
            assertNoRun(next, flow, "outer-record");
            assertNoRun(next, flow, "record");
            approve(fixture, flow, id, "deep", "APPROVED");
            Execution completed = query(fixture, id);
            for (int level = 1; level <= 4; level++) {
                assertNoRun(completed, flow, "unused-" + level);
                assertEquals(State.Type.SUCCESS, latestRun(completed, flow, "level-" + level).state().current());
            }
            assertEquals(1, countRuns(completed, flow, "before"));
            assertEquals(2, countRuns(completed, flow, "first"));
            assertEquals(2, countRuns(completed, flow, "deep"));
            assertEquals(2, countRuns(completed, flow, "notify"));
            assertEquals(1, countRuns(completed, flow, "outer-record"));
            assertObservedDecision(completed, flow, "outer-record", "first", "NEW");
            assertObservedDecision(completed, flow, "record", "deep", "APPROVED");
            assertCompletedRewinds(fixture, flow, id, "deep-to-outer");
        }
    }

    /** Tests a waiting parallel sibling keeps its exact occurrence and remains resumable. */
    @Test
    void s11BranchRewindKeepsTheOtherPausedBranchValid() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(rewindYaml("uc10-s11-flow",
                parallelYaml(routeYaml("first-branch", "{{ inputs.amount }} > 400",
                    approvalYaml("first") + approvalYaml("second")) + approvalYaml("sibling"))
                    + recordYaml("record", "first", "second", "sibling")));
            String id = startWithAmount(fixture, flow, 500).id();
            Execution waiting = approve(fixture, flow, id, "first", "OLD");
            PausedTaskRunRef sibling = pausedTask(query(fixture, id), flow, "sibling");
            TaskRun target = onlyRun(waiting, flow, "first");
            var attempt = fixture.rewind(pausedTask(query(fixture, id), flow, "second"),
                target.id(), "parallel-branch");
            Execution rewound = query(fixture, id);
            assertPausedKeys(rewound, flow, "first", "sibling");
            assertEquals(sibling.taskRunId(), pausedTask(rewound, flow, "sibling").taskRunId());
            assertFalse(attempt.accepted().affectedTaskRunIds().contains(sibling.taskRunId()));
            String newFirst = pausedTask(rewound, flow, "first").taskRunId();
            PausedTaskRunRef queriedSibling = pausedTask(query(fixture, id), flow, "sibling");
            Execution afterSibling = fixture.resume(queriedSibling, Map.of("decision", "KEPT"));
            assertPausedKeys(afterSibling, flow, "first");
            assertEquals(newFirst, pausedTask(afterSibling, flow, "first").taskRunId());
            assertNoRun(afterSibling, flow, "record");
            Execution next = approve(fixture, flow, id, "first", "NEW");
            assertPausedKeys(next, flow, "second");
            assertEquals(sibling.taskRunId(), onlyRun(next, flow, "sibling").id());
            assertEquals(Map.of("decision", "KEPT"), onlyRun(next, flow, "sibling").outputs());
            assertNoRun(next, flow, "record");
            approve(fixture, flow, id, "second", "APPROVED");
            Execution completed = query(fixture, id);
            assertEquals(2, countRuns(completed, flow, "first"));
            assertEquals(2, countRuns(completed, flow, "second"));
            assertEquals(sibling.taskRunId(), onlyRun(completed, flow, "sibling").id());
            assertObservedDecision(completed, flow, "record", "first", "NEW");
            assertObservedDecision(completed, flow, "record", "sibling", "KEPT");
            assertCompletedRewinds(fixture, flow, id, "parallel-branch");
        }
    }

    /** Tests an explicitly selected earlier-finishing parallel predecessor is replayed alone. */
    @Test
    void s12AfterParallelMergeRewindsOnlyTheSelectedPredecessor() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(rewindYaml("uc10-s12-flow",
                parallelYaml(approvalYaml("first") + approvalYaml("sibling"))
                    + approvalYaml("final", "first", "sibling")
                    + recordYaml("record", "first", "sibling")));
            String id = startWithAmount(fixture, flow, 500).id();
            approve(fixture, flow, id, "first", "OLD");
            Execution waiting = approve(fixture, flow, id, "sibling", "KEPT");
            TaskRun target = onlyRun(waiting, flow, "first");
            TaskRun sibling = onlyRun(waiting, flow, "sibling");
            PausedTaskRunRef source = pausedTask(query(fixture, id), flow, "final");
            assertEquals(Set.of(target.id(), sibling.id()),
                fixture.executionService().previousCompletedTaskRuns(fixture.session(), id,
                    source.taskRunId(), Set.of("first", "sibling", "final"))
                    .stream().map(TaskRun::id).collect(Collectors.toSet()));
            assertEquals("first", fixture.executionService().planRewind(
                fixture.session(), id, source.taskRunId(), target.id()).targetTaskKey());
            assertEquals("sibling", fixture.executionService().planRewind(
                fixture.session(), id, source.taskRunId(), sibling.id()).targetTaskKey());
            var attempt = fixture.rewind(source, target.id(), "selected-parallel");
            Execution rewound = query(fixture, id);
            assertPausedKeys(rewound, flow, "first");
            assertEquals(sibling.id(), onlyRun(rewound, flow, "sibling").id());
            assertEquals(State.Type.SUCCESS, onlyRun(rewound, flow, "sibling").state().current());
            assertEquals(sibling.outputs(), onlyRun(rewound, flow, "sibling").outputs());
            assertFalse(attempt.accepted().affectedTaskRunIds().contains(sibling.id()));
            assertNoRun(rewound, flow, "record");
            Execution next = approve(fixture, flow, id, "first", "NEW");
            assertPausedKeys(next, flow, "final");
            assertObservedDecision(next, flow, "prepare-final", "first", "NEW");
            assertObservedDecision(next, flow, "prepare-final", "sibling", "KEPT");
            approve(fixture, flow, id, "final", "APPROVED");
            Execution completed = query(fixture, id);
            assertEquals(2, countRuns(completed, flow, "first"));
            assertEquals(2, countRuns(completed, flow, "final"));
            assertEquals(sibling.id(), onlyRun(completed, flow, "sibling").id());
            assertObservedDecision(completed, flow, "record", "first", "NEW");
            assertObservedDecision(completed, flow, "record", "sibling", "KEPT");
            assertCompletedRewinds(fixture, flow, id, "selected-parallel");
        }
    }

    /** Tests repeated cross-condition rewinds reject superseded target occurrences. */
    @Test
    void s13RepeatedConditionalRewindUsesOnlyTheCurrentEffectiveApprovals() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(rewindYaml("uc10-s13-flow", logYaml("before")
                + routeYaml("outer", "{{ inputs.amount }} > 400",
                    routeYaml("inner", "{{ inputs.amount }} > 400", approvalYaml("first"))
                        + routeYaml("inner-skip", "{{ inputs.amount }} <= 400", approvalYaml("unused-inner")))
                + routeYaml("outer-skip", "{{ inputs.amount }} <= 400", approvalYaml("unused-outer"))
                + approvalYaml("final", "first") + recordYaml("record", "first")));
            String id = startWithAmount(fixture, flow, 500).id();
            Execution initial = approve(fixture, flow, id, "first", "OLD");
            TaskRun oldTarget = onlyRun(initial, flow, "first");
            PausedTaskRunRef oldSource = pausedTask(query(fixture, id), flow, "final");
            fixture.rewind(oldSource, oldTarget.id(), "first-correction");
            approve(fixture, flow, id, "first", "INTERMEDIATE");
            Execution waiting = query(fixture, id);
            PausedTaskRunRef source = pausedTask(waiting, flow, "final");
            TaskRun target = latestRun(waiting, flow, "first");
            assertRejectedWithoutChange(fixture, id, () -> fixture.executionService().rewind(
                fixture.session(), id, source.taskRunId(), oldTarget.id(), "stale-target"));
            var attempt = fixture.rewind(source, target.id(), "second-correction");
            assertFalse(attempt.accepted().affectedTaskRunIds().contains(oldTarget.id()));
            assertFalse(attempt.accepted().affectedTaskRunIds().contains(oldSource.taskRunId()));
            assertPausedKeys(query(fixture, id), flow, "first");
            assertRejectedWithoutChange(fixture, id, () -> fixture.executionService().resume(
                fixture.session(), id, oldSource.taskRunId(), Map.of("decision", "OLD")));
            assertRejectedWithoutChange(fixture, id, () -> fixture.executionService().resume(
                fixture.session(), id, source.taskRunId(), Map.of("decision", "INTERMEDIATE")));
            approve(fixture, flow, id, "first", "NEWEST");
            Execution finalWaiting = query(fixture, id);
            assertPausedKeys(finalWaiting, flow, "final");
            assertObservedDecision(finalWaiting, flow, "prepare-final", "first", "NEWEST");
            approve(fixture, flow, id, "final", "APPROVED");
            Execution completed = query(fixture, id);
            assertEquals(1, countRuns(completed, flow, "before"));
            assertEquals(3, countRuns(completed, flow, "first"));
            assertEquals(3, countRuns(completed, flow, "final"));
            assertNoRun(completed, flow, "unused-inner");
            assertNoRun(completed, flow, "unused-outer");
            assertObservedDecision(completed, flow, "record", "first", "NEWEST");
            assertCompletedRewinds(fixture, flow, id, "first-correction", "second-correction");
        }
    }

    /**
     * Builds an explicit parallel scope for independent approval branches.
     * @param children YAML task list of the concurrent branches
     * @return YAML task list with one parallel scope
     */
    private static String parallelYaml(String children) {
        return """
            - key: parallel-stage
              type: org.cses.flow.extensions.flow.Parallel
              tasks:
            %s
            """.formatted(children.indent(4));
    }

    /**
     * Publishes a start request with a selected amount and queries its stable state.
     * @param fixture real Flow test session
     * @param flow published definition to select
     * @param amount fixed amount used by the conditional paths
     * @return observed stable execution
     */
    private static Execution startWithAmount(WorkflowUcFixture fixture, Flow flow, int amount) {
        var accepted = fixture.executionService().create(
            fixture.session(), flow.key(), Optional.of(flow.version()), Map.of("amount", amount));
        return fixture.awaitStable(accepted.getExecutionId());
    }

    /**
     * Queries the current named approval before submitting its result.
     * @param fixture real Flow test session
     * @param flow published definition used to identify the approval
     * @param executionId selected runtime instance
     * @param key approval business key
     * @param decision approval result consumed by later steps
     * @return observed execution after the approval settles
     */
    private static Execution approve(
        WorkflowUcFixture fixture, Flow flow, String executionId, String key, String decision
    ) {
        return fixture.resume(pausedTask(query(fixture, executionId), flow, key),
            Map.of("decision", decision));
    }

    /**
     * Checks the exact set of user-visible paused approvals in a queried execution.
     * @param execution queried runtime snapshot
     * @param flow published definition used to resolve business keys
     * @param expected expected approval keys, in any order
     */
    private static void assertPausedKeys(Execution execution, Flow flow, String... expected) {
        assertEquals(List.of(expected).stream().sorted().toList(),
            execution.pausedTaskRuns().stream()
                .filter(run -> flow.findTask(run.taskId()).orElseThrow() instanceof Pause)
                .map(run -> flow.findTask(run.taskId()).orElseThrow().key()).sorted().toList());
    }

    /**
     * Checks a rejected public request and re-queries its unchanged visible state.
     * @param fixture real Flow test session
     * @param executionId selected runtime instance
     * @param request invalid public operation expected to fail
     */
    private static void assertRejectedWithoutChange(
        WorkflowUcFixture fixture, String executionId, Runnable request
    ) {
        Execution before = query(fixture, executionId);
        assertThrows(WorkflowException.class, request::run);
        Execution after = query(fixture, executionId);
        assertEquals(before.state().current(), after.state().current());
        assertEquals(before.taskRuns().stream().map(TaskRun::id).toList(),
            after.taskRuns().stream().map(TaskRun::id).toList());
        assertEquals(before.pausedTaskRuns().stream().map(TaskRun::id).toList(),
            after.pausedTaskRuns().stream().map(TaskRun::id).toList());
        assertEquals(before.taskRuns().stream().map(TaskRun::outputs).toList(),
            after.taskRuns().stream().map(TaskRun::outputs).toList());
    }

    /**
     * Checks that a later step consumed the selected approval result.
     * @param execution queried runtime snapshot
     * @param flow published definition
     * @param observer key of the step that consumed the result
     * @param approval key of the approval whose result is expected
     * @param decision expected result value
     */
    private static void assertObservedDecision(
        Execution execution, Flow flow, String observer, String approval, String decision
    ) {
        assertEquals(decision, latestRun(execution, flow, observer).outputs().get(approval));
    }

    /**
     * Queries completion, exactly one final record, and all persisted rewind reasons.
     * @param fixture real Flow test session
     * @param flow published definition
     * @param executionId selected runtime instance
     * @param reasons expected rewind reasons in chronological order
     */
    private static void assertCompletedRewinds(
        WorkflowUcFixture fixture, Flow flow, String executionId, String... reasons
    ) {
        Execution completed = query(fixture, executionId);
        assertEquals(State.Type.SUCCESS, completed.state().current());
        assertEquals(1, countRuns(completed, flow, "record"));
        assertTrue(completed.generation().current().isEmpty());
        assertEquals(List.of(reasons), completed.generation().history().currents().stream()
            .map(current -> current.reason()).toList());
        assertNoPendingWork(fixture, executionId);
    }

    /**
     * Builds an amount-driven Flow with the explicitly provided ordered steps.
     * @param key unique business key for this scenario
     * @param tasks YAML list of the scenario's steps
     * @return complete publishable Flow YAML
     */
    private static String rewindYaml(String key, String tasks) {
        return """
            key: %s
            inputs:
              - key: amount
                type: INTEGER
                required: true
            tasks:
            %s
            """.formatted(key, tasks.indent(2));
    }

    /**
     * Builds one deterministic approval Pause with its visible preparation step.
     * @param key approval business key
     * @param observed approval keys whose results are recorded by its preparation
     * @return YAML task list with one approval
     */
    private static String approvalYaml(String key, String... observed) {
        String preparation = observed.length == 0
            ? logYaml("prepare-" + key)
            : recordYaml("prepare-" + key, observed);
        return """
            - key: %s
              type: org.cses.flow.extensions.flow.Pause
              pause:
            %s
              resume:
                - key: decision
                  type: STRING
              outputs:
                - key: decision
                  type: STRING
            """.formatted(key, preparation.substring(2).indent(-2).indent(4));
    }

    /**
     * Builds a real Worker step that publishes the approval results it consumed.
     * @param key record step key
     * @param approvals approval keys to expose as string outputs
     * @return publishable YAML task with explicitly declared observation outputs
     */
    private static String recordYaml(String key, String... approvals) {
        String outputs = List.of(approvals).stream()
            .map(approval -> "    - key: " + approval + "\n      type: STRING\n")
            .collect(Collectors.joining());
        return """
            - key: %s
              type: %s
              outputs:
            %s
            """.formatted(key, ApprovalSnapshot.class.getCanonicalName(), outputs);
    }

    /** Records only declared approval decisions from the actual Worker context. */
    @Plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class ApprovalSnapshot extends Task implements RunnableTask {

        /**
         * Copies effective approval decisions into visible results without altering the flow.
         * @param context actual Worker context for this record step
         * @return declared string results; an unmatched approval is represented by an empty value
         */
        @Override
        public RunResult run(RunContext context) {
            Map<?, ?> previous = (Map<?, ?>) context.variables().get("outputs");
            Map<String, Object> observed = new LinkedHashMap<>();
            outputs().forEach(output -> {
                Object result = previous.get(output.getKey());
                observed.put(output.getKey(), result instanceof Map<?, ?> values
                    && values.get("decision") != null ? values.get("decision") : "");
            });
            return RunResult.success(observed);
        }
    }

    /**
     * Builds an automatic observable step without approval behavior.
     * @param key business key of the notification or record
     * @return YAML task list with one log step
     */
    private static String logYaml(String key) {
        return """
            - key: %s
              type: org.cses.flow.extensions.log.Log
              message: observe approval results
            """.formatted(key);
    }

    /**
     * Builds a conditional scope with the given ordered children.
     * @param key conditional scope key
     * @param condition expression evaluated from the fixed application amount
     * @param children YAML task list nested under this condition
     * @return YAML task list with one conditional scope
     */
    private static String routeYaml(String key, String condition, String children) {
        return """
            - key: %s
              type: org.cses.flow.extensions.flow.Route
              route: '%s'
              tasks:
            %s
            """.formatted(key, condition, children.indent(4));
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
