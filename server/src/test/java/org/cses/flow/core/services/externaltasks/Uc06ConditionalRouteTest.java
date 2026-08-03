package org.cses.flow.core.services.externaltasks;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.externaltasks.ExternalTask;
import org.cses.flow.core.domains.externaltasks.ExternalTaskStatus;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.services.executions.WorkflowUcFixture;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC: docs/uc/flow/UC-06 用户提交外派结果后的条件路径.md
 */
class Uc06ConditionalRouteTest {

    @Test
    void s1RunsOnlyApprovedBranch() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            RouteScenario scenario = start(fixture, "uc06-s1-flow");
            Execution completed = fixture.externalTaskService().complete(
                fixture.session(),
                scenario.externalTask().id(),
                Map.of("decision", "APPROVED")
            );

            // PASS-S1-01
            assertEquals(
                Map.of("decision", "APPROVED"),
                run(completed, task(scenario.flow(), "approval-decision"))
                    .outputs()
            );
            // PASS-S1-02
            assertEquals(
                State.Type.COMPLETED,
                run(completed, task(scenario.flow(), "approve")).state().current()
            );
            // PASS-S1-03
            assertNoRun(completed, task(scenario.flow(), "reject"));
            assertEquals(State.Type.COMPLETED, completed.state().current());
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s2RunsOnlyRejectedBranch() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            RouteScenario scenario = start(fixture, "uc06-s2-flow");
            Execution completed = fixture.externalTaskService().complete(
                fixture.session(),
                scenario.externalTask().id(),
                Map.of("decision", "REJECTED")
            );

            // PASS-S2-01
            assertEquals(
                Map.of("decision", "REJECTED"),
                run(completed, task(scenario.flow(), "approval-decision"))
                    .outputs()
            );
            // PASS-S2-02
            assertEquals(
                State.Type.COMPLETED,
                run(completed, task(scenario.flow(), "reject")).state().current()
            );
            assertNoRun(completed, task(scenario.flow(), "approve"));
            assertEquals(State.Type.COMPLETED, completed.state().current());
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s3NoMatchCompletesWithoutCreatingCandidateRuns() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            RouteScenario scenario = start(fixture, "uc06-s3-flow");
            Execution completed = fixture.externalTaskService().complete(
                fixture.session(),
                scenario.externalTask().id(),
                Map.of("decision", "UNKNOWN")
            );

            // PASS-S3-01
            assertNoRun(completed, task(scenario.flow(), "approve"));
            assertNoRun(completed, task(scenario.flow(), "reject"));
            // PASS-S3-02
            assertEquals(State.Type.COMPLETED, completed.state().current());
            assertEquals(1, completed.taskRuns().size());
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s4RejectsInvalidRouteWhenDeployingFlow() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            // PASS-S4-01
            FlowDraft draft = fixture.flowService().saveDraft(
                fixture.session(),
                routeYaml(
                    "uc06-s4-flow",
                    "outputs.decision =="
                )
            );
            IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.flowService().deploy(
                    fixture.session(),
                    draft.id()
                )
            );
            assertTrue(failure.getMessage().contains("route expression"));
            assertTrue(
                fixture.flowService()
                    .latestFlow(fixture.session(), draft.id())
                    .isEmpty()
            );
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
    void s5MissingOutputDoesNotSelectAnyBranch() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            RouteScenario scenario = start(fixture, "uc06-s5-flow");
            Execution completed = fixture.externalTaskService().complete(
                fixture.session(),
                scenario.externalTask().id(),
                Map.of()
            );

            // PASS-S5-01
            assertNoRun(completed, task(scenario.flow(), "approve"));
            assertNoRun(completed, task(scenario.flow(), "reject"));
            // PASS-S5-02
            assertTrue(
                run(completed, task(scenario.flow(), "approval-decision"))
                    .outputs().isEmpty()
            );
            assertEquals(State.Type.COMPLETED, completed.state().current());
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s6RouteComparisonIsCaseSensitive() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            RouteScenario scenario = start(fixture, "uc06-s6-flow");
            Execution completed = fixture.externalTaskService().complete(
                fixture.session(),
                scenario.externalTask().id(),
                Map.of("decision", "approved")
            );

            // PASS-S6-01
            assertEquals(
                "approved",
                run(completed, task(scenario.flow(), "approval-decision"))
                    .outputs().get("decision")
            );
            // PASS-S6-02
            assertNoRun(completed, task(scenario.flow(), "approve"));
            assertNoRun(completed, task(scenario.flow(), "reject"));
            assertEquals(State.Type.COMPLETED, completed.state().current());
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s7ExternalTaskIdRoutesOnlyItsOwningExecution() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(routeYaml(
                "uc06-s7-flow",
                "outputs.decision == \"APPROVED\""
            ));
            Execution first = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            Execution second = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            fixture.restartServer();
            ExternalTask firstExternal = waiting(fixture, first);
            ExternalTask secondExternal = waiting(fixture, second);

            Execution selected = fixture.externalTaskService().complete(
                fixture.session(),
                secondExternal.id(),
                Map.of("decision", "APPROVED")
            );

            // PASS-S7-01
            assertEquals(second.id(), selected.id());
            assertEquals(
                State.Type.COMPLETED,
                run(selected, task(flow, "approve")).state().current()
            );
            // PASS-S7-02
            Execution untouched = fixture.executionService().execution(
                fixture.session(),
                first.id()
            ).orElseThrow();
            assertEquals(State.Type.WAITING, untouched.state().current());
            assertEquals(
                State.Type.WAITING,
                untouched.taskRuns().getFirst().state().current()
            );
            assertTrue(untouched.taskRuns().getFirst().outputs().isEmpty());
            assertEquals(
                ExternalTaskStatus.WAITING,
                fixture.externalTaskService().externalTask(
                    fixture.session(),
                    firstExternal.id()
                ).orElseThrow().status()
            );

            fixture.restartServer();
            ExternalTask remaining =
                fixture.waitingForExecution(first.id());
            Execution firstCompleted =
                fixture.externalTaskService().complete(
                    fixture.session(),
                    remaining.id(),
                    Map.of("decision", "APPROVED")
            );
            assertEquals(State.Type.COMPLETED, firstCompleted.state().current());
            assertEquals(State.Type.COMPLETED, selected.state().current());
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    private static RouteScenario start(
        WorkflowUcFixture fixture,
        String key
    ) {
        Flow flow = fixture.deploy(routeYaml(
            key,
            "outputs.decision == \"APPROVED\""
        ));
        Execution execution = fixture.executionService().create(
            fixture.session(),
            flow.id()
        );
        fixture.restartServer();
        return new RouteScenario(
            flow,
            execution,
            waiting(fixture, execution)
        );
    }

    private static ExternalTask waiting(
        WorkflowUcFixture fixture,
        Execution execution
    ) {
        return fixture.externalTaskService().waitingTasks(
            fixture.session()
        ).stream()
            .filter(task ->
                task.executionId().equals(execution.id())
            )
            .findFirst()
            .orElseThrow();
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
                flatten(task.tasks()).stream()
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

    private static String routeYaml(String key, String approveRoute) {
        return """
            key: %s
            description: conditional approval
            tasks:
              - key: approval-decision
                type: PAUSE
                outputs:
                  - key: decision
                    type: STRING
                tasks:
                  - key: approve
                    type: AUTO
                    route: '%s'
                  - key: reject
                    type: AUTO
                    route: 'outputs.decision == "REJECTED"'
            """.formatted(key, approveRoute);
    }

    private static final class RouteScenario {

        private final Flow flow;
        private final Execution execution;
        private final ExternalTask externalTask;

        private RouteScenario(
            Flow flow,
            Execution execution,
            ExternalTask externalTask
        ) {
            this.flow = flow;
            this.execution = execution;
            this.externalTask = externalTask;
        }

        private Flow flow() {
            return flow;
        }

        private Execution execution() {
            return execution;
        }

        private ExternalTask externalTask() {
            return externalTask;
        }
    }
}
