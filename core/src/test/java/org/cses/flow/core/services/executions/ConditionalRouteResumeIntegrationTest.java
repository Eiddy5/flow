package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.tasks.Task;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.cses.flow.core.services.executions.WorkflowUcFixture.PausedTaskRunRef;

class ConditionalRouteResumeIntegrationTest {

    @Test
    void approvedOutputRunsOnlyApprovedBranch() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            RouteScenario scenario = start(
                fixture,
                "conditional-resume-approved-flow"
            );
            Execution completed = fixture.resume(
                scenario.pausedTaskRun(),
                Map.of("decision", "APPROVED")
            );

            assertEquals(
                Map.of("decision", "APPROVED"),
                run(completed, task(scenario.flow(), "approval-decision"))
                    .outputs()
            );
            assertEquals(
                State.Type.SUCCESS,
                run(completed, task(scenario.flow(), "approve")).state().current()
            );
            assertRouteState(
                completed,
                task(scenario.flow(), "route-decision"),
                State.Type.SUCCESS
            );
            assertRouteState(
                completed,
                task(scenario.flow(), "reject-decision"),
                State.Type.SKIPPED
            );
            assertNoRun(completed, task(scenario.flow(), "reject"));
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void rejectedOutputRunsOnlyRejectedBranch() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            RouteScenario scenario = start(
                fixture,
                "conditional-resume-rejected-flow"
            );
            Execution completed = fixture.resume(
                scenario.pausedTaskRun(),
                Map.of("decision", "REJECTED")
            );

            assertEquals(
                Map.of("decision", "REJECTED"),
                run(completed, task(scenario.flow(), "approval-decision"))
                    .outputs()
            );
            assertEquals(
                State.Type.SUCCESS,
                run(completed, task(scenario.flow(), "reject")).state().current()
            );
            assertRouteState(
                completed,
                task(scenario.flow(), "route-decision"),
                State.Type.SKIPPED
            );
            assertRouteState(
                completed,
                task(scenario.flow(), "reject-decision"),
                State.Type.SUCCESS
            );
            assertNoRun(completed, task(scenario.flow(), "approve"));
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void unmatchedOutputSkipsRouteRunsWithoutChildRuns() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            RouteScenario scenario = start(
                fixture,
                "conditional-resume-unmatched-flow"
            );
            Execution completed = fixture.resume(
                scenario.pausedTaskRun(),
                Map.of("decision", "UNKNOWN")
            );

            assertNoRun(completed, task(scenario.flow(), "approve"));
            assertNoRun(completed, task(scenario.flow(), "reject"));
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(4, completed.taskRuns().size());
            assertRouteState(
                completed,
                task(scenario.flow(), "route-decision"),
                State.Type.SKIPPED
            );
            assertRouteState(
                completed,
                task(scenario.flow(), "reject-decision"),
                State.Type.SKIPPED
            );
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void missingOutputDoesNotSelectAnyBranch() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            RouteScenario scenario = start(
                fixture,
                "conditional-resume-missing-output-flow"
            );
            Execution completed = fixture.resume(
                scenario.pausedTaskRun(),
                Map.of()
            );

            assertNoRun(completed, task(scenario.flow(), "approve"));
            assertNoRun(completed, task(scenario.flow(), "reject"));
            assertTrue(
                run(completed, task(scenario.flow(), "approval-decision"))
                    .outputs().isEmpty()
            );
            assertRouteState(
                completed,
                task(scenario.flow(), "route-decision"),
                State.Type.SKIPPED
            );
            assertRouteState(
                completed,
                task(scenario.flow(), "reject-decision"),
                State.Type.SKIPPED
            );
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void routeComparisonIsCaseSensitive() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            RouteScenario scenario = start(
                fixture,
                "conditional-resume-case-sensitive-flow"
            );
            Execution completed = fixture.resume(
                scenario.pausedTaskRun(),
                Map.of("decision", "approved")
            );

            assertEquals(
                "approved",
                run(completed, task(scenario.flow(), "approval-decision"))
                    .outputs().get("decision")
            );
            assertNoRun(completed, task(scenario.flow(), "approve"));
            assertNoRun(completed, task(scenario.flow(), "reject"));
            assertRouteState(
                completed,
                task(scenario.flow(), "route-decision"),
                State.Type.SKIPPED
            );
            assertRouteState(
                completed,
                task(scenario.flow(), "reject-decision"),
                State.Type.SKIPPED
            );
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void taskRunIdRoutesOnlyOwningExecution() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(routeYaml(
                "conditional-resume-isolation-flow",
                "{{ outputs.approval-decision.decision }} == APPROVED"
            ));
            Execution first = fixture.startCreated(flow);
            Execution second = fixture.startCreated(flow);
            fixture.restartServer();
            PausedTaskRunRef firstPause = waiting(fixture, first);
            PausedTaskRunRef secondPause = waiting(fixture, second);

            Execution selected = fixture.resume(
                secondPause,
                Map.of("decision", "APPROVED")
            );

            assertEquals(second.id(), selected.id());
            assertEquals(
                State.Type.SUCCESS,
                run(selected, task(flow, "approve")).state().current()
            );
            Execution untouched = fixture.executionService().execution(
                fixture.session(),
                first.id()
            ).orElseThrow();
            assertEquals(State.Type.PAUSED, untouched.state().current());
            assertEquals(
                State.Type.PAUSED,
                untouched.taskRuns().getFirst().state().current()
            );
            assertTrue(untouched.taskRuns().getFirst().outputs().isEmpty());
            assertEquals(
                State.Type.PAUSED,
                fixture.taskRun(firstPause).state().current()
            );

            fixture.restartServer();
            PausedTaskRunRef remaining =
                fixture.waitingForExecution(first.id());
            Execution firstCompleted = fixture.resume(
                remaining,
                Map.of("decision", "APPROVED")
            );
            assertEquals(State.Type.SUCCESS, firstCompleted.state().current());
            assertEquals(State.Type.SUCCESS, selected.state().current());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    private static RouteScenario start(
        WorkflowUcFixture fixture,
        String key
    ) {
        Flow flow = fixture.deploy(routeYaml(
            key,
            "{{ outputs.approval-decision.decision }} == APPROVED"
        ));
        Execution execution = fixture.startCreated(flow);
        fixture.restartServer();
        return new RouteScenario(
            flow,
            execution,
            waiting(fixture, execution)
        );
    }

    private static PausedTaskRunRef waiting(
        WorkflowUcFixture fixture,
        Execution execution
    ) {
        return fixture.waitingForExecution(execution.id());
    }

    private static Task task(Flow flow, String key) {
        return flatten(flow.tasks()).stream()
            .filter(task -> task.key().equals(key))
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
            .filter(run -> run.taskId().equals(task.id()))
            .findFirst()
            .orElseThrow();
    }

    private static void assertNoRun(Execution execution, Task task) {
        assertTrue(execution.taskRuns().stream()
            .noneMatch(run -> run.taskId().equals(task.id())));
    }

    private static void assertRouteState(
        Execution execution,
        Task task,
        State.Type expected
    ) {
        TaskRun taskRun = run(execution, task);
        assertEquals(expected, taskRun.state().current());
        assertEquals(
            List.of(
                State.Type.CREATED,
                State.Type.RUNNING,
                expected
            ),
            taskRun.state().history().stream()
                .map(State.History::state)
                .toList()
        );
        if (expected == State.Type.SKIPPED) {
            assertTrue(taskRun.outputs().isEmpty());
            assertTrue(taskRun.error().isEmpty());
        }
    }

    private static String routeYaml(String key, String approveExpress) {
        return """
            key: %s
            description: conditional approval
            tasks:
              - key: approval-decision
                type: org.cses.flow.extensions.flow.Pause
                onPause:
                  key: create-approval-decision
                  type: org.cses.flow.extensions.log.Log
                  message: "test step"
                onResume:
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
            """.formatted(key, approveExpress);
    }

    private static final class RouteScenario {

        private final Flow flow;
        private final Execution execution;
        private final PausedTaskRunRef pausedTaskRun;

        private RouteScenario(
            Flow flow,
            Execution execution,
            PausedTaskRunRef pausedTaskRun
        ) {
            this.flow = flow;
            this.execution = execution;
            this.pausedTaskRun = pausedTaskRun;
        }

        private Flow flow() {
            return flow;
        }

        private Execution execution() {
            return execution;
        }

        private PausedTaskRunRef pausedTaskRun() {
            return pausedTaskRun;
        }
    }
}
