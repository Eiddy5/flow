package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.cses.flow.core.services.executions.WorkflowUcFixture.PausedTaskRunRef;

/**
 * UC: docs/uc/flow/UC-06 用户使用 Pause 结果选择 Flow 路径.md
 */
class Uc06ConditionalRouteTest {

    @Test
    void s1PauseApprovalSelectsOnlyApprovalPath() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(routeYaml(
                "uc06-s1-flow", "{{ outputs.approval-decision.decision }} == APPROVED"
            ));
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef pause = fixture.waitingForExecution(started.id());
            Execution completed = fixture.resume(
                pause, Map.of("decision", "APPROVED")
            );

            assertEquals(Map.of("decision", "APPROVED"),
                run(completed, flow, "approval-decision").outputs());
            assertEquals(State.Type.SUCCESS,
                run(completed, flow, "approve").state().current());
            assertNoRun(completed, flow, "reject");
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s2PauseRejectionSelectsOnlyRejectionPath() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(routeYaml(
                "uc06-s2-flow", "{{ outputs.approval-decision.decision }} == APPROVED"
            ));
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef pause = fixture.waitingForExecution(started.id());
            Execution completed = fixture.resume(
                pause, Map.of("decision", "REJECTED")
            );

            assertEquals(Map.of("decision", "REJECTED"),
                run(completed, flow, "approval-decision").outputs());
            assertEquals(State.Type.SUCCESS,
                run(completed, flow, "reject").state().current());
            assertNoRun(completed, flow, "approve");
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s3UnknownPauseResultSelectsNoPathAndStillCompletes() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(routeYaml(
                "uc06-s3-flow", "{{ outputs.approval-decision.decision }} == APPROVED"
            ));
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            Execution completed = fixture.resume(
                fixture.waitingForExecution(started.id()),
                Map.of("decision", "UNKNOWN")
            );

            assertNoRun(completed, flow, "approve");
            assertNoRun(completed, flow, "reject");
            assertEquals(State.Type.SUCCESS,
                run(completed, flow, "route-decision").state().current());
            assertEquals(State.Type.SUCCESS,
                run(completed, flow, "reject-decision").state().current());
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s4InvalidRouteConditionIsRejectedBeforePublishing() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow draft = fixture.flowService().save(
                fixture.session(),
                PublishFlowCommand.from(routeYaml("uc06-s4-flow", "{{ outputs.decision }} =="))
            );

            IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.flowService().save(
                    fixture.session(), PublishFlowCommand.from(draft.key(), false)
                )
            );
            assertTrue(failure.getMessage().contains("Condition"));
            assertTrue(fixture.flowService().latestFlow(
                fixture.session(), draft.key()).isEmpty());
            assertTrue(fixture.executionService().executions(fixture.session())
                .isEmpty());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s5MissingDecisionSelectsNoPathWithoutDefaulting() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(routeYaml(
                "uc06-s5-flow", "{{ outputs.approval-decision.decision }} == APPROVED"
            ));
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            Execution completed = fixture.resume(
                fixture.waitingForExecution(started.id()), Map.of()
            );

            assertEquals(Map.of(),
                run(completed, flow, "approval-decision").outputs());
            assertNoRun(completed, flow, "approve");
            assertNoRun(completed, flow, "reject");
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s6LowercaseDecisionDoesNotMatchUppercasePaths() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(routeYaml(
                "uc06-s6-flow", "{{ outputs.approval-decision.decision }} == APPROVED"
            ));
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            Execution completed = fixture.resume(
                fixture.waitingForExecution(started.id()),
                Map.of("decision", "approved")
            );

            assertEquals("approved",
                run(completed, flow, "approval-decision").outputs().get("decision"));
            assertNoRun(completed, flow, "approve");
            assertNoRun(completed, flow, "reject");
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s7SelectingOneInstanceDoesNotAffectTheOtherInstance() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(routeYaml(
                "uc06-s7-flow", "{{ outputs.approval-decision.decision }} == APPROVED"
            ));
            Execution first = fixture.startAndAwait(flow);
            Execution second = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef firstPause = fixture.waitingForExecution(first.id());
            PausedTaskRunRef secondPause = fixture.waitingForExecution(second.id());

            Execution secondCompleted = fixture.resume(
                secondPause, Map.of("decision", "APPROVED")
            );
            Execution firstWaiting = fixture.executionService().execution(
                fixture.session(), first.id()).orElseThrow();
            assertEquals(State.Type.SUCCESS, secondCompleted.state().current());
            assertEquals(State.Type.PAUSED, firstWaiting.state().current());
            assertEquals(Map.of(), fixture.taskRun(firstPause).outputs());
            assertNoRun(secondCompleted, flow, "reject");

            fixture.restartServer();
            Execution firstCompleted = fixture.resume(
                fixture.waitingForExecution(first.id()),
                Map.of("decision", "REJECTED")
            );
            assertEquals(State.Type.SUCCESS, firstCompleted.state().current());
            assertEquals(Map.of("decision", "REJECTED"),
                run(firstCompleted, flow, "approval-decision").outputs());
            assertEquals(State.Type.SUCCESS,
                run(firstCompleted, flow, "reject").state().current());
            assertEquals(State.Type.SUCCESS,
                run(secondCompleted, flow, "approve").state().current());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    private static Task task(Flow flow, String key) {
        return flow.allTasks().stream()
            .filter(candidate -> candidate.key().equals(key))
            .findFirst()
            .orElseThrow();
    }

    private static TaskRun run(Execution execution, Flow flow, String key) {
        return execution.taskRunsForTask(task(flow, key).id()).stream()
            .findFirst()
            .orElseThrow();
    }

    private static void assertNoRun(Execution execution, Flow flow, String key) {
        assertEquals(0, execution.taskRunsForTask(task(flow, key).id()).size());
    }

    private static String routeYaml(String key, String approveExpression) {
        return """
            key: %s
            description: Pause result chooses a route
            tasks:
              - key: approval-decision
                type: org.cses.flow.extensions.flow.Pause
                pause:
                  key: create-approval-decision
                  type: org.cses.flow.extensions.log.Log
                  message: "test step"
                resume:
                  - key: decision
                    type: STRING
                outputs:
                  - key: decision
                    type: STRING
              - key: route-decision
                type: org.cses.flow.extensions.flow.Route
                route: '%s'
                tasks:
                  - key: approve
                    type: org.cses.flow.extensions.log.Log
                    message: "test step"
              - key: reject-decision
                type: org.cses.flow.extensions.flow.Route
                route: '{{ outputs.approval-decision.decision }} == REJECTED'
                tasks:
                  - key: reject
                    type: org.cses.flow.extensions.log.Log
                    message: "test step"
            """.formatted(key, approveExpression);
    }
}
