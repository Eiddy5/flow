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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.cses.flow.core.services.executions.WorkflowUcFixture.PausedTaskRunRef;

/**
 * UC: docs/uc/flow/UC-05 用户处理包含并行 Pause 的 Flow.md
 */
class Uc05ParallelPauseFlowTest {

    @Test
    void s1UserResumesBothParallelPausesAndJoinsOnce() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(parallelYaml("uc05-s1-flow"));
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef backend = fixture.waitingForOutput(
                started.id(), "backendResult");
            PausedTaskRunRef frontend = fixture.waitingForOutput(
                started.id(), "frontendResult");
            assertNotEquals(backend.taskRunId(), frontend.taskRunId());

            Execution afterBackend = fixture.resume(
                backend, Map.of("backendResult", "PASS")
            );
            assertEquals(State.Type.PAUSED, afterBackend.state().current());
            assertEquals(State.Type.PAUSED,
                fixture.taskRun(frontend).state().current());
            assertNoRun(afterBackend, flow, "join-checks");
            assertNoRun(afterBackend, flow, "finish");

            fixture.restartServer();
            PausedTaskRunRef currentFrontend = fixture.waitingForOutput(
                started.id(), "frontendResult");
            Execution completed = fixture.resume(
                currentFrontend, Map.of("frontendResult", "PASS")
            );
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(1, countRuns(completed, flow, "join-checks"));
            assertEquals(1, countRuns(completed, flow, "finish"));
            assertEquals(Map.of("backendResult", "PASS"),
                run(completed, flow, "backend-check").outputs());
            assertEquals(Map.of("frontendResult", "PASS"),
                run(completed, flow, "frontend-check").outputs());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s2JoinAppearsOnlyAfterTheLastParallelPauseResumes() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(parallelYaml("uc05-s2-flow"));
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef backend = fixture.waitingForOutput(
                started.id(), "backendResult");

            Execution afterFirst = fixture.resume(
                backend, Map.of("backendResult", "PASS")
            );
            assertNoRun(afterFirst, flow, "join-checks");
            assertNoRun(afterFirst, flow, "finish");
            assertEquals(State.Type.PAUSED,
                run(afterFirst, flow, "frontend-check").state().current());

            fixture.restartServer();
            PausedTaskRunRef frontend = fixture.waitingForOutput(
                started.id(), "frontendResult");
            Execution completed = fixture.resume(
                frontend, Map.of("frontendResult", "PASS")
            );
            assertEquals(1, countRuns(completed, flow, "join-checks"));
            assertEquals(1, countRuns(completed, flow, "finish"));
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s3OneUnresumedParallelPauseBlocksJoinAndFinish() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(parallelYaml("uc05-s3-flow"));
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef backend = fixture.waitingForOutput(
                started.id(), "backendResult");
            fixture.resume(backend, Map.of("backendResult", "PASS"));
            Execution middle = fixture.executionService().execution(
                fixture.session(), started.id()).orElseThrow();

            assertEquals(State.Type.PAUSED, middle.state().current());
            assertEquals(State.Type.PAUSED,
                run(middle, flow, "frontend-check").state().current());
            assertNoRun(middle, flow, "join-checks");
            assertNoRun(middle, flow, "finish");

            fixture.restartServer();
            Execution completed = fixture.resume(
                fixture.waitingForOutput(started.id(), "frontendResult"),
                Map.of("frontendResult", "PASS")
            );
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(1, countRuns(completed, flow, "join-checks"));
            assertEquals(1, countRuns(completed, flow, "finish"));
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s4RepeatedResumeOfOneParallelPauseIsRejected() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(parallelYaml("uc05-s4-flow"));
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef backend = fixture.waitingForOutput(
                started.id(), "backendResult");
            fixture.resume(backend, Map.of("backendResult", "PASS"));

            assertThrows(WorkflowException.class, () -> fixture.resume(
                backend, Map.of("backendResult", "PASS")));
            Execution afterRejected = fixture.executionService().execution(
                fixture.session(), started.id()).orElseThrow();
            assertEquals(State.Type.PAUSED, afterRejected.state().current());
            assertEquals(1, countRuns(afterRejected, flow, "backend-check"));
            assertNoRun(afterRejected, flow, "join-checks");
            assertNoRun(afterRejected, flow, "finish");

            fixture.restartServer();
            Execution completed = fixture.resume(
                fixture.waitingForOutput(started.id(), "frontendResult"),
                Map.of("frontendResult", "PASS")
            );
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(1, countRuns(completed, flow, "join-checks"));
            assertEquals(1, countRuns(completed, flow, "finish"));
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s5ReversedResumeOrderHasEquivalentResultsAndOneJoin() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(parallelYaml("uc05-s5-flow"));
            Execution backendFirst = completeBoth(fixture, flow, true);
            Execution frontendFirst = completeBoth(fixture, flow, false);

            assertEquals(State.Type.SUCCESS, backendFirst.state().current());
            assertEquals(State.Type.SUCCESS, frontendFirst.state().current());
            assertEquals(1, countRuns(backendFirst, flow, "join-checks"));
            assertEquals(1, countRuns(frontendFirst, flow, "join-checks"));
            assertEquals(
                run(backendFirst, flow, "backend-check").outputs(),
                run(frontendFirst, flow, "backend-check").outputs()
            );
            assertEquals(
                run(backendFirst, flow, "frontend-check").outputs(),
                run(frontendFirst, flow, "frontend-check").outputs()
            );
            assertNotEquals(backendFirst.id(), frontendFirst.id());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s6CancelAfterOneParallelResumeRejectsTheRemainingResume() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(parallelYaml("uc05-s6-flow"));
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef backend = fixture.waitingForOutput(
                started.id(), "backendResult");
            PausedTaskRunRef frontend = fixture.waitingForOutput(
                started.id(), "frontendResult");
            fixture.resume(backend, Map.of("backendResult", "PASS"));
            Execution canceled = fixture.cancel(started.id());

            assertThrows(WorkflowException.class, () -> fixture.resume(
                frontend, Map.of("frontendResult", "PASS")));
            Execution reloaded = fixture.executionService().execution(
                fixture.session(), started.id()).orElseThrow();
            assertEquals(State.Type.KILLED, canceled.state().current());
            assertEquals(State.Type.KILLED, reloaded.state().current());
            assertEquals(State.Type.KILLED,
                run(reloaded, flow, "frontend-check").state().current());
            assertNoRun(reloaded, flow, "join-checks");
            assertNoRun(reloaded, flow, "finish");
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
        PausedTaskRunRef first = fixture.waitingForOutput(
            started.id(), backendFirst ? "backendResult" : "frontendResult");
        Execution middle = fixture.resume(
            first,
            backendFirst
                ? Map.of("backendResult", "PASS")
                : Map.of("frontendResult", "PASS")
        );
        assertNoRun(middle, flow, "join-checks");
        fixture.restartServer();
        PausedTaskRunRef second = fixture.waitingForOutput(
            started.id(), backendFirst ? "frontendResult" : "backendResult");
        return fixture.resume(
            second,
            backendFirst
                ? Map.of("frontendResult", "PASS")
                : Map.of("backendResult", "PASS")
        );
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

    private static long countRuns(Execution execution, Flow flow, String key) {
        return execution.taskRunsForTask(task(flow, key).id()).size();
    }

    private static void assertNoRun(Execution execution, Flow flow, String key) {
        assertEquals(0, execution.taskRunsForTask(task(flow, key).id()).size());
    }

    private static String parallelYaml(String key) {
        return """
            key: %s
            description: explicit parallel Pause checks
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
