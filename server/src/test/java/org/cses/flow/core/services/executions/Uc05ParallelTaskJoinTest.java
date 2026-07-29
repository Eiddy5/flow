package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.ExecutionStatus;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.executions.TaskRunStatus;
import org.cses.flow.core.domains.externaltasks.ExternalTask;
import org.cses.flow.core.domains.externaltasks.ExternalTaskStatus;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.shared.WorkflowException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC: docs/uc/flow/UC-05 并行 Task 调度与汇合.md
 */
class Uc05ParallelTaskJoinTest {

    @Test
    void s1UserCompletesTwoWaitingBranchesAndJoinsOnce() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.publish(parallelYaml("uc05-s1-flow"));
            Execution execution = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );

            TaskRun fork = run(execution, task(flow, "start-checks"));
            TaskRun backend = run(execution, task(flow, "backend-check"));
            TaskRun frontend = run(execution, task(flow, "frontend-check"));

            // PASS-S1-01
            assertNotNull(execution.id());
            assertEquals(ExecutionStatus.RUNNING, execution.status());
            assertEquals(TaskRunStatus.COMPLETED, fork.status());
            assertEquals(TaskRunStatus.RUNNING, backend.status());
            assertEquals(TaskRunStatus.RUNNING, frontend.status());
            assertEquals(fork.id(), backend.parentId());
            assertEquals(fork.id(), frontend.parentId());

            fixture.restartServer();
            // PASS-S1-02
            assertEquals(
                ExternalTaskStatus.WAITING,
                waiting(fixture, backend).status()
            );
            assertEquals(
                ExternalTaskStatus.WAITING,
                waiting(fixture, frontend).status()
            );

            fixture.externalTaskService().complete(
                fixture.session(),
                waiting(fixture, backend).id(),
                Map.of("backendResult", "PASS")
            );
            fixture.restartServer();
            ExternalTask remaining = fixture.waitingForOutput(
                execution.id(),
                "frontendResult"
            );
            Execution completed =
                fixture.externalTaskService().complete(
                    fixture.session(),
                    remaining.id(),
                    Map.of("frontendResult", "PASS")
                );

            // PASS-S1-03
            assertEquals(ExecutionStatus.COMPLETED, completed.status());
            assertEquals(1, countRuns(
                completed,
                task(flow, "join-checks")
            ));
            assertEquals(1, countRuns(completed, task(flow, "finish")));
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s2JoinsExactlyOnceAfterBothBranchesComplete() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.publish(parallelYaml("uc05-s2-flow"));
            Execution started = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            TaskRun backend = run(started, task(flow, "backend-check"));
            TaskRun frontend = run(started, task(flow, "frontend-check"));
            fixture.restartServer();
            ExternalTask backendExternal = waiting(fixture, backend);

            Execution afterFirst = fixture.externalTaskService().complete(
                fixture.session(),
                backendExternal.id(),
                Map.of("backendResult", "PASS")
            );
            // PASS-S2-01
            assertNoRun(afterFirst, task(flow, "join-checks"));

            fixture.restartServer();
            ExternalTask frontendExternal = fixture.waitingForOutput(
                started.id(),
                "frontendResult"
            );
            Execution completed = fixture.externalTaskService().complete(
                fixture.session(),
                frontendExternal.id(),
                Map.of("frontendResult", "PASS")
            );

            // PASS-S2-02
            assertEquals(1, countRuns(completed, task(flow, "join-checks")));
            assertEquals(
                Map.of("backendResult", "PASS"),
                run(completed, task(flow, "backend-check")).outputs()
            );
            assertEquals(
                Map.of("frontendResult", "PASS"),
                run(completed, task(flow, "frontend-check")).outputs()
            );
            // PASS-S2-03
            assertEquals(1, countRuns(completed, task(flow, "finish")));
            assertEquals(ExecutionStatus.COMPLETED, completed.status());
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
            assertEquals(
                List.of(
                    "start-checks",
                    "backend-check",
                    "frontend-check",
                    "join-checks",
                    "finish"
                ),
                completed.taskRuns().stream()
                    .map(run -> taskById(flow, run.taskId()).key())
                    .toList()
            );
        }
    }

    @Test
    void s3DoesNotJoinWhenOnlyOneBranchCompletes() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.publish(parallelYaml("uc05-s3-flow"));
            Execution started = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            TaskRun backend = run(started, task(flow, "backend-check"));
            fixture.restartServer();

            Execution current = fixture.externalTaskService().complete(
                fixture.session(),
                waiting(fixture, backend).id(),
                Map.of("backendResult", "PASS")
            );

            // PASS-S3-01
            assertEquals(
                TaskRunStatus.RUNNING,
                run(current, task(flow, "frontend-check")).status()
            );
            assertNoRun(current, task(flow, "join-checks"));
            assertNoRun(current, task(flow, "finish"));

            fixture.restartServer();
            ExternalTask remaining = fixture.waitingForOutput(
                started.id(),
                "frontendResult"
            );
            Execution completed =
                fixture.externalTaskService().complete(
                    fixture.session(),
                    remaining.id(),
                    Map.of("frontendResult", "PASS")
                );
            // PASS-S3-02
            assertEquals(ExecutionStatus.COMPLETED, completed.status());
        }
    }

    @Test
    void s4RejectsRepeatedBranchCompletionWithoutJoining() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.publish(parallelYaml("uc05-s4-flow"));
            Execution started = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            fixture.restartServer();
            ExternalTask backend = waiting(
                fixture,
                run(started, task(flow, "backend-check"))
            );
            fixture.externalTaskService().complete(
                fixture.session(),
                backend.id(),
                Map.of("backendResult", "PASS")
            );

            // PASS-S4-01
            assertThrows(
                WorkflowException.class,
                () -> fixture.externalTaskService().complete(
                    fixture.session(),
                    backend.id(),
                    Map.of("backendResult", "PASS")
                )
            );
            Execution reloaded = fixture.executionService().execution(
                fixture.session(),
                started.id()
            ).orElseThrow();
            assertEquals(
                TaskRunStatus.COMPLETED,
                run(reloaded, task(flow, "backend-check")).status()
            );
            assertNoRun(reloaded, task(flow, "join-checks"));
            assertNoRun(reloaded, task(flow, "finish"));

            fixture.restartServer();
            ExternalTask remaining = fixture.waitingForOutput(
                started.id(),
                "frontendResult"
            );
            Execution completed =
                fixture.externalTaskService().complete(
                    fixture.session(),
                    remaining.id(),
                    Map.of("frontendResult", "PASS")
                );
            // PASS-S4-02
            assertEquals(ExecutionStatus.COMPLETED, completed.status());
        }
    }

    @Test
    void s5BranchCompletionOrderDoesNotChangeJoinResult() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.publish(parallelYaml("uc05-s5-flow"));
            Execution first = completeBoth(fixture, flow, true);
            Execution second = completeBoth(fixture, flow, false);

            // PASS-S5-01
            assertEquals(
                first.taskRuns().stream().map(TaskRun::taskId).toList(),
                second.taskRuns().stream().map(TaskRun::taskId).toList()
            );
            assertEquals(ExecutionStatus.COMPLETED, first.status());
            assertEquals(ExecutionStatus.COMPLETED, second.status());
            // PASS-S5-02
            assertEquals(1, countRuns(first, task(flow, "join-checks")));
            assertEquals(1, countRuns(second, task(flow, "join-checks")));
            assertEquals(
                run(first, task(flow, "backend-check")).outputs(),
                run(second, task(flow, "backend-check")).outputs()
            );
            assertEquals(
                run(first, task(flow, "frontend-check")).outputs(),
                run(second, task(flow, "frontend-check")).outputs()
            );
        }
    }

    @Test
    void s6CancellationStopsRemainingBranchAndPreventsJoin() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.publish(parallelYaml("uc05-s6-flow"));
            Execution started = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            TaskRun backendRun = run(started, task(flow, "backend-check"));
            TaskRun frontendRun = run(started, task(flow, "frontend-check"));
            fixture.restartServer();
            ExternalTask backend = waiting(fixture, backendRun);
            ExternalTask frontend = waiting(fixture, frontendRun);
            fixture.externalTaskService().complete(
                fixture.session(),
                backend.id(),
                Map.of("backendResult", "PASS")
            );

            Execution canceled = fixture.executionService().cancel(
                fixture.session(),
                started.id()
            );

            // PASS-S6-01
            assertEquals(ExecutionStatus.CANCELED, canceled.status());
            assertEquals(
                TaskRunStatus.CANCELED,
                run(canceled, task(flow, "frontend-check")).status()
            );
            assertEquals(
                ExternalTaskStatus.CANCELED,
                fixture.externalTaskService().externalTask(
                    fixture.session(),
                    frontend.id()
                ).orElseThrow().status()
            );
            // PASS-S6-02
            assertThrows(
                WorkflowException.class,
                () -> fixture.externalTaskService().complete(
                    fixture.session(),
                    frontend.id(),
                    Map.of("frontendResult", "PASS")
                )
            );
            Execution reloaded = fixture.executionService().execution(
                fixture.session(),
                started.id()
            ).orElseThrow();
            assertNoRun(reloaded, task(flow, "join-checks"));
            assertNoRun(reloaded, task(flow, "finish"));
        }
    }

    private static Execution completeBoth(
        WorkflowUcFixture fixture,
        Flow flow,
        boolean backendFirst
    ) {
        Execution started = fixture.executionService().create(
            fixture.session(),
            flow.id()
        );
        fixture.restartServer();
        ExternalTask backend = waiting(
            fixture,
            run(started, task(flow, "backend-check"))
        );
        ExternalTask frontend = waiting(
            fixture,
            run(started, task(flow, "frontend-check"))
        );
        if (backendFirst) {
            Execution afterFirst =
                fixture.externalTaskService().complete(
                fixture.session(),
                backend.id(),
                Map.of("backendResult", "PASS")
            );
            assertNoRun(afterFirst, task(flow, "join-checks"));
            fixture.restartServer();
            frontend = fixture.waitingForOutput(
                started.id(),
                "frontendResult"
            );
            return fixture.externalTaskService().complete(
                fixture.session(),
                frontend.id(),
                Map.of("frontendResult", "PASS")
            );
        }
        Execution afterFirst =
            fixture.externalTaskService().complete(
            fixture.session(),
            frontend.id(),
            Map.of("frontendResult", "PASS")
        );
        assertNoRun(afterFirst, task(flow, "join-checks"));
        fixture.restartServer();
        backend = fixture.waitingForOutput(
            started.id(),
            "backendResult"
        );
        return fixture.externalTaskService().complete(
            fixture.session(),
            backend.id(),
            Map.of("backendResult", "PASS")
        );
    }

    private static Task task(Flow flow, String key) {
        return flatten(flow.tasks()).stream()
            .filter(task -> task.key().equals(key))
            .findFirst()
            .orElseThrow();
    }

    private static Task taskById(Flow flow, String id) {
        return flatten(flow.tasks()).stream()
            .filter(task -> task.id().equals(id))
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

    private static long countRuns(Execution execution, Task task) {
        return execution.taskRuns().stream()
            .filter(run -> run.taskId().equals(task.id()))
            .count();
    }

    private static ExternalTask waiting(
        WorkflowUcFixture fixture,
        TaskRun taskRun
    ) {
        return fixture.externalTaskService().waitingTasks(
            fixture.session()
        ).stream()
            .filter(task -> task.taskRunId().equals(taskRun.id()))
            .findFirst()
            .orElseThrow();
    }

    private static String parallelYaml(String key) {
        return """
            key: %s
            description: parallel checks
            tasks:
              - key: start-checks
                type: AUTO
                tasks:
                  - key: backend-check
                    type: PAUSE
                    outputs:
                      - key: backendResult
                        type: STRING
                  - key: frontend-check
                    type: PAUSE
                    outputs:
                      - key: frontendResult
                        type: STRING
              - key: join-checks
                type: AUTO
                dependOn:
                  - backend-check
                  - frontend-check
              - key: finish
                type: AUTO
            """.formatted(key);
    }
}
