package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.cses.flow.core.services.executions.WorkflowUcFixture.PausedTaskRunRef;

class ParallelPauseResumeIntegrationTest {

    /**
     * Resumes a queried parallel Pause while another branch is running. The
     * running branch is released afterward; both results must be retained and
     * the join must execute once.
     *
     * @throws InterruptedException when the controlled Worker wait is interrupted
     */
    @Test
    void resumesPausedBranchWhileSiblingWorkerIsRunning()
        throws InterruptedException {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.openWithProperties(
            Map.of("flow.test.workflow-task", true)
        )) {
            Flow flow = fixture.deploy("""
                key: parallel-running-sibling-resume
                description: resume one Pause while its sibling is running
                tasks:
                  - key: start-checks
                    type: org.cses.flow.extensions.flow.Parallel
                    tasks:
                      - key: backend-check
                        type: org.cses.flow.extensions.flow.Pause
                        onPause:
                          key: create-backend-check
                          type: org.cses.flow.extensions.log.Log
                          message: "backend ready"
                        onResume:
                          - key: backendResult
                            type: STRING
                      - key: frontend-branch
                        type: org.cses.flow.extensions.flow.Sequence
                        tasks:
                          - key: frontend-check
                            type: org.cses.flow.extensions.flow.Pause
                            onPause:
                              key: create-frontend-check
                              type: org.cses.flow.extensions.log.Log
                              message: "frontend ready"
                            onResume:
                              - key: frontendResult
                                type: STRING
                          - key: target-block
                            type: org.cses.flow.core.services.executions.TestWorkflowTask
                  - key: join-checks
                    type: org.cses.flow.extensions.log.Log
                    message: "checks joined"
                  - key: finish
                    type: org.cses.flow.extensions.log.Log
                    message: "finished"
                """);
            Execution started = fixture.startAndAwait(flow);
            PausedTaskRunRef frontend = fixture.waitingForOutput(
                started.id(), "frontendResult"
            );
            TestWorkflowTask.blockNextRun();
            try {
                fixture.executionService().resume(
                    fixture.session(), started.id(), frontend.taskRunId(),
                    Map.of("frontendResult", "PASS")
                );
                assertTrue(TestWorkflowTask.awaitBlockingRun());

                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    Execution running = fixture.executionService().execution(
                        fixture.session(), started.id()
                    ).orElseThrow();
                    assertEquals(State.Type.RUNNING, running.state().current());
                    TaskRun backend = run(running, task(flow, "backend-check"));
                    assertEquals(State.Type.PAUSED, backend.state().current());
                    PausedTaskRunRef currentBackend = waiting(fixture, backend);
                    assertNoRun(running, task(flow, "join-checks"));
                    fixture.executionService().resume(
                        fixture.session(), started.id(), currentBackend.taskRunId(),
                        Map.of("backendResult", "PASS")
                    );
                });
            } finally {
                TestWorkflowTask.releaseBlockingRun();
            }

            Execution completed = fixture.awaitExecution(
                fixture.session(), started.id(), Execution::isTerminal
            );
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(Map.of("backendResult", "PASS"),
                run(completed, task(flow, "backend-check")).outputs());
            assertEquals(Map.of("frontendResult", "PASS"),
                run(completed, task(flow, "frontend-check")).outputs());
            assertEquals(1, countRuns(completed, task(flow, "target-block")));
            assertEquals(1, countRuns(completed, task(flow, "join-checks")));
            assertEquals(1, countRuns(completed, task(flow, "finish")));
            assertTrue(completed.activeTaskRuns().isEmpty());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void resumingBothParallelBranchesJoinsOnce() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(parallelYaml(
                "parallel-resume-both-flow"
            ));
            Execution execution = fixture.startAndAwait(flow);

            TaskRun fork = run(execution, task(flow, "start-checks"));
            TaskRun backend = run(execution, task(flow, "backend-check"));
            TaskRun frontend = run(execution, task(flow, "frontend-check"));

            assertNotNull(execution.id());
            assertEquals(State.Type.PAUSED, execution.state().current());
            assertEquals(State.Type.PAUSED, fork.state().current());
            assertEquals(State.Type.PAUSED, backend.state().current());
            assertEquals(State.Type.PAUSED, frontend.state().current());
            assertEquals(fork.id(), backend.parentId().orElseThrow());
            assertEquals(fork.id(), frontend.parentId().orElseThrow());

            fixture.restartServer();
            assertEquals(
                State.Type.PAUSED,
                fixture.taskRun(waiting(fixture, backend)).state().current()
            );
            assertEquals(
                State.Type.PAUSED,
                fixture.taskRun(waiting(fixture, frontend)).state().current()
            );

            fixture.resume(
                waiting(fixture, backend),
                Map.of("backendResult", "PASS")
            );
            fixture.restartServer();
            PausedTaskRunRef remaining = fixture.waitingForOutput(
                execution.id(),
                "frontendResult"
            );
            Execution completed = fixture.resume(
                remaining,
                Map.of("frontendResult", "PASS")
            );

            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(1, countRuns(
                completed,
                task(flow, "join-checks")
            ));
            assertEquals(1, countRuns(completed, task(flow, "finish")));
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void joinOccursOnlyAfterBothBranchesResume() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(parallelYaml(
                "parallel-resume-join-order-flow"
            ));
            Execution started = fixture.startAndAwait(flow);
            TaskRun backend = run(started, task(flow, "backend-check"));
            TaskRun frontend = run(started, task(flow, "frontend-check"));
            fixture.restartServer();
            PausedTaskRunRef backendPause = waiting(fixture, backend);

            Execution afterFirst = fixture.resume(
                backendPause,
                Map.of("backendResult", "PASS")
            );
            assertNoRun(afterFirst, task(flow, "join-checks"));

            fixture.restartServer();
            PausedTaskRunRef frontendPause = fixture.waitingForOutput(
                started.id(),
                "frontendResult"
            );
            Execution completed = fixture.resume(
                frontendPause,
                Map.of("frontendResult", "PASS")
            );

            assertEquals(1, countRuns(completed, task(flow, "join-checks")));
            assertEquals(
                Map.of("backendResult", "PASS"),
                run(completed, task(flow, "backend-check")).outputs()
            );
            assertEquals(
                Map.of("frontendResult", "PASS"),
                run(completed, task(flow, "frontend-check")).outputs()
            );
            assertEquals(1, countRuns(completed, task(flow, "finish")));
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
            assertEquals(
                List.of(
                    "start-checks",
                    "backend-check",
                    "frontend-check",
                    "create-backend-check",
                    "create-frontend-check",
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
    void singleResumedBranchDoesNotJoin() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(parallelYaml(
                "parallel-resume-single-branch-flow"
            ));
            Execution started = fixture.startAndAwait(flow);
            TaskRun backend = run(started, task(flow, "backend-check"));
            fixture.restartServer();

            Execution current = fixture.resume(
                waiting(fixture, backend),
                Map.of("backendResult", "PASS")
            );

            assertEquals(
                State.Type.PAUSED,
                run(current, task(flow, "frontend-check")).state().current()
            );
            assertNoRun(current, task(flow, "join-checks"));
            assertNoRun(current, task(flow, "finish"));

            fixture.restartServer();
            PausedTaskRunRef remaining = fixture.waitingForOutput(
                started.id(),
                "frontendResult"
            );
            Execution completed = fixture.resume(
                remaining,
                Map.of("frontendResult", "PASS")
            );
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(1, countRuns(completed, task(flow, "join-checks")));
            assertEquals(1, countRuns(completed, task(flow, "finish")));
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void repeatedResumeDoesNotJoinAgain() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(parallelYaml(
                "parallel-resume-repeated-flow"
            ));
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef backend = waiting(
                fixture,
                run(started, task(flow, "backend-check"))
            );
            fixture.resume(
                backend,
                Map.of("backendResult", "PASS")
            );

            assertThrows(
                WorkflowException.class,
                () -> fixture.resume(
                    backend,
                    Map.of("backendResult", "PASS")
                )
            );
            Execution reloaded = fixture.executionService().execution(
                fixture.session(),
                started.id()
            ).orElseThrow();
            assertEquals(
                State.Type.SUCCESS,
                run(reloaded, task(flow, "backend-check")).state().current()
            );
            assertNoRun(reloaded, task(flow, "join-checks"));
            assertNoRun(reloaded, task(flow, "finish"));

            fixture.restartServer();
            PausedTaskRunRef remaining = fixture.waitingForOutput(
                started.id(),
                "frontendResult"
            );
            Execution completed = fixture.resume(
                remaining,
                Map.of("frontendResult", "PASS")
            );
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(1, countRuns(completed, task(flow, "join-checks")));
            assertEquals(1, countRuns(completed, task(flow, "finish")));
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void branchResumeOrderDoesNotChangeJoinResult() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(parallelYaml(
                "parallel-resume-reversed-order-flow"
            ));
            Execution first = completeBoth(fixture, flow, true);
            Execution second = completeBoth(fixture, flow, false);

            assertEquals(
                first.taskRuns().stream().map(TaskRun::taskId).toList(),
                second.taskRuns().stream().map(TaskRun::taskId).toList()
            );
            assertEquals(State.Type.SUCCESS, first.state().current());
            assertEquals(State.Type.SUCCESS, second.state().current());
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
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void cancellationStopsRemainingBranchAndPreventsJoin() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(parallelYaml(
                "parallel-resume-cancellation-flow"
            ));
            Execution started = fixture.startAndAwait(flow);
            TaskRun backendRun = run(started, task(flow, "backend-check"));
            TaskRun frontendRun = run(started, task(flow, "frontend-check"));
            fixture.restartServer();
            PausedTaskRunRef backend = waiting(fixture, backendRun);
            PausedTaskRunRef frontend = waiting(fixture, frontendRun);
            fixture.resume(
                backend,
                Map.of("backendResult", "PASS")
            );

            Execution canceled = fixture.cancel(started.id());

            assertEquals(State.Type.KILLED, canceled.state().current());
            assertEquals(
                State.Type.KILLED,
                run(canceled, task(flow, "frontend-check")).state().current()
            );
            assertEquals(
                State.Type.KILLED,
                canceled.requireTaskRun(
                    frontend.taskRunId()
                ).state().current()
            );
            assertThrows(
                WorkflowException.class,
                () -> fixture.resume(
                    frontend,
                    Map.of("frontendResult", "PASS")
                )
            );
            Execution reloaded = fixture.executionService().execution(
                fixture.session(),
                started.id()
            ).orElseThrow();
            assertNoRun(reloaded, task(flow, "join-checks"));
            assertNoRun(reloaded, task(flow, "finish"));
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    private static Execution completeBoth(
        WorkflowUcFixture fixture,
        Flow flow,
        boolean backendFirst
    ) {
        Execution started = fixture.startAndAwait(flow);
        fixture.restartServer();
        PausedTaskRunRef backend = waiting(
            fixture,
            run(started, task(flow, "backend-check"))
        );
        PausedTaskRunRef frontend = waiting(
            fixture,
            run(started, task(flow, "frontend-check"))
        );
        if (backendFirst) {
            Execution afterFirst = fixture.resume(
                backend,
                Map.of("backendResult", "PASS")
            );
            assertNoRun(afterFirst, task(flow, "join-checks"));
            fixture.restartServer();
            frontend = fixture.waitingForOutput(
                started.id(),
                "frontendResult"
            );
            return fixture.resume(
                frontend,
                Map.of("frontendResult", "PASS")
            );
        }
        Execution afterFirst = fixture.resume(
            frontend,
            Map.of("frontendResult", "PASS")
        );
        assertNoRun(afterFirst, task(flow, "join-checks"));
        fixture.restartServer();
        backend = fixture.waitingForOutput(
            started.id(),
            "backendResult"
        );
        return fixture.resume(
            backend,
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

    private static long countRuns(Execution execution, Task task) {
        return execution.taskRuns().stream()
            .filter(run -> run.taskId().equals(task.id()))
            .count();
    }

    private static PausedTaskRunRef waiting(
        WorkflowUcFixture fixture,
        TaskRun taskRun
    ) {
        return fixture.pausedTaskRuns().stream()
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
                type: org.cses.flow.extensions.flow.Parallel
                tasks:
                  - key: backend-check
                    type: org.cses.flow.extensions.flow.Pause
                    onPause:
                      key: create-backend-check
                      type: org.cses.flow.extensions.log.Log
                      message: "test step"
                    onResume:
                      - key: backendResult
                        type: STRING
                  - key: frontend-check
                    type: org.cses.flow.extensions.flow.Pause
                    onPause:
                      key: create-frontend-check
                      type: org.cses.flow.extensions.log.Log
                      message: "test step"
                    onResume:
                      - key: frontendResult
                        type: STRING
              - key: join-checks
                type: org.cses.flow.extensions.log.Log
                message: "test step"
              - key: finish
                type: org.cses.flow.extensions.log.Log
                message: "test step"
            """.formatted(key);
    }
}
