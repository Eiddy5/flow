package org.cses.flow.core.services.executions;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.runner.RunContext;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.cses.flow.core.services.executions.WorkflowUcFixture.PausedTaskRunRef;

/**
 * UC: docs/uc/flow/UC-04 用户恢复处于 Pause 的 Flow.md
 */
class Uc04PauseFlowTest {

    @Test
    void s1UserQueriesAndResumesOnePausedFlow() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(pauseFlowYaml("uc04-s1-flow", false,
                false));
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef pause = fixture.waitingForExecution(started.id());

            Execution completed = fixture.resume(
                pause, Map.of("decision", "APPROVED")
            );
            Execution reloaded = query(fixture, started.id());
            Task after = task(flow, "record-result");

            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(State.Type.SUCCESS, reloaded.state().current());
            assertEquals(Map.of("decision", "APPROVED"),
                reloaded.requireTaskRun(pause.taskRunId()).outputs());
            assertEquals(Map.of(
                "outputs", Map.of("wait-confirmation",
                    Map.of("decision", "APPROVED"))
            ), run(reloaded, after).inputs());
            assertEquals(3, reloaded.taskRuns().size());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s2ResumeResultIsConsumedOnceByOnlyTheCurrentFlow() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(pauseFlowYaml("uc04-s2-flow", false,
                false));
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef pause = fixture.waitingForExecution(started.id());

            Execution completed = fixture.resume(
                pause, Map.of("decision", "APPROVED")
            );
            Execution reloaded = query(fixture, started.id());

            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(1, reloaded.taskRunsForTask(
                task(flow, "wait-confirmation").id()).size());
            assertEquals(1, reloaded.taskRunsForTask(
                task(flow, "record-result").id()).size());
            assertEquals(Map.of("decision", "APPROVED"),
                run(reloaded, task(flow, "wait-confirmation")).outputs());
            assertEquals(State.Type.SUCCESS,
                run(reloaded, task(flow, "record-result")).state().current());
            assertEquals(State.Type.SUCCESS, reloaded.state().current());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s3IllegalResumeInputsAreRejectedUntilValidResultIsSubmitted() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(pauseFlowYaml("uc04-s3-flow", true,
                false));
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef pause = fixture.waitingForExecution(started.id());
            assertPausedAndUnchanged(fixture, started.id(), pause, 2);

            assertThrows(WorkflowException.class, () -> fixture.executionService()
                .resume(fixture.session(), "missing-execution", pause.taskRunId(),
                    Map.of("decision", "APPROVED")));
            assertPausedAndUnchanged(fixture, started.id(), pause, 2);

            assertThrows(IllegalArgumentException.class, () -> fixture.executionService()
                .resume(fixture.session(), started.id(), " ", Map.of()));
            assertPausedAndUnchanged(fixture, started.id(), pause, 2);

            assertThrows(WorkflowException.class, () -> fixture.executionService()
                .resume(fixture.session(), started.id(), pause.taskRunId(), Map.of()));
            assertPausedAndUnchanged(fixture, started.id(), pause, 2);

            Execution completed = fixture.resume(
                pause, Map.of("decision", "APPROVED")
            );
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s4RepeatedResumeOfFinishedPauseIsRejectedWithoutMutation() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(pauseFlowYaml("uc04-s4-flow", false,
                false));
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef pause = fixture.waitingForExecution(started.id());
            Execution completed = fixture.resume(
                pause, Map.of("decision", "APPROVED")
            );
            Execution before = query(fixture, started.id());

            assertThrows(WorkflowException.class, () -> fixture.executionService()
                .resume(fixture.session(), pause.executionId(), pause.taskRunId(),
                    Map.of("decision", "REJECTED")));
            Execution after = query(fixture, started.id());

            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(before.state(), after.state());
            assertEquals(before.taskRuns().size(), after.taskRuns().size());
            assertEquals(before.taskRuns().stream().map(TaskRun::id).toList(),
                after.taskRuns().stream().map(TaskRun::id).toList());
            assertEquals(before.requireTaskRun(pause.taskRunId()).outputs(),
                after.requireTaskRun(pause.taskRunId()).outputs());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s5OtherTenantCannotQueryOrResumeThePausedFlow() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(pauseFlowYaml("uc04-s5-flow", false,
                false));
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef pause = fixture.waitingForExecution(started.id());
            Session<User> other = fixture.sessionFor("other");

            assertTrue(fixture.executionService().execution(other, started.id())
                .isEmpty());
            assertTrue(fixture.pausedTaskRuns(other).isEmpty());
            assertThrows(WorkflowException.class, () -> fixture.executionService()
                .resume(other, started.id(), pause.taskRunId(),
                    Map.of("decision", "APPROVED")));
            assertPausedAndUnchanged(fixture, started.id(), pause, 2);

            Execution completed = fixture.resume(
                pause, Map.of("decision", "APPROVED")
            );
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s6SameUserResumesTheSelectedInstanceOnly() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(pauseFlowYaml("uc04-s6-flow", false,
                false));
            Execution first = fixture.startAndAwait(flow);
            Execution second = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef firstPause = fixture.waitingForExecution(first.id());
            PausedTaskRunRef secondPause = fixture.waitingForExecution(second.id());

            Execution secondCompleted = fixture.resume(
                secondPause, Map.of("decision", "SECOND")
            );
            Execution firstWaiting = query(fixture, first.id());
            assertEquals(State.Type.SUCCESS, secondCompleted.state().current());
            assertEquals(State.Type.PAUSED, firstWaiting.state().current());
            assertEquals(State.Type.PAUSED,
                fixture.taskRun(firstPause).state().current());
            assertEquals(Map.of(), fixture.taskRun(firstPause).outputs());

            fixture.restartServer();
            PausedTaskRunRef firstRequeried = fixture.waitingForExecution(first.id());
            assertEquals(firstPause.taskRunId(), firstRequeried.taskRunId());
            Execution firstCompleted = fixture.resume(
                firstRequeried, Map.of("decision", "FIRST")
            );
            assertEquals(State.Type.SUCCESS, firstCompleted.state().current());
            assertEquals(Map.of("decision", "FIRST"),
                firstCompleted.requireTaskRun(firstPause.taskRunId()).outputs());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s7CancelledFlowRejectsResumeOfItsFormerPause() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(pauseFlowYaml("uc04-s7-flow", false,
                false));
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef pause = fixture.waitingForExecution(started.id());
            Execution canceled = fixture.cancel(started.id());

            assertThrows(WorkflowException.class, () -> fixture.executionService()
                .resume(fixture.session(), pause.executionId(), pause.taskRunId(),
                    Map.of("decision", "APPROVED")));
            Execution reloaded = query(fixture, started.id());
            assertEquals(State.Type.KILLED, canceled.state().current());
            assertEquals(State.Type.KILLED, reloaded.state().current());
            assertTrue(reloaded.taskRuns().stream().noneMatch(taskRun ->
                taskRun.state().is(State.Type.PAUSED)));
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s8DownstreamFailureMustLeavePauseWaitingAndNotPartiallyAdvance() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy("""
                key: uc04-s8-flow
                description: downstream failure after pause
                tasks:
                  - key: wait-confirmation
                    type: org.cses.flow.extensions.flow.Pause
                    pause:
                      key: create-confirmation
                      type: org.cses.flow.extensions.log.Log
                      message: "test step"
                    resume:
                      - key: decision
                        type: STRING
                        required: true
                    outputs:
                      - key: decision
                        type: STRING
                  - key: explode
                    type: org.cses.flow.core.services.executions.Uc04PauseFlowTest.ThrowingTask
                  - key: never-run
                    type: org.cses.flow.extensions.log.Log
                    message: "test step"
                """);
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef pause = fixture.waitingForExecution(started.id());

            Execution afterResume = fixture.resume(
                pause, Map.of("decision", "APPROVED")
            );
            Execution reloaded = query(fixture, started.id());

            assertEquals(State.Type.PAUSED, afterResume.state().current());
            assertEquals(State.Type.PAUSED, reloaded.state().current());
            assertEquals(Map.of(), reloaded.requireTaskRun(pause.taskRunId())
                .outputs());
            assertTrue(reloaded.taskRuns().stream().noneMatch(taskRun ->
                taskRun.taskId().equals(task(flow, "explode").id())));
            assertTrue(reloaded.taskRuns().stream().noneMatch(taskRun ->
                taskRun.taskId().equals(task(flow, "never-run").id())));
        }
    }

    @Test
    void s9FreshServerCanRequeryAndResumeTheSamePause() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(pauseFlowYaml("uc04-s9-flow", false,
                false));
            Execution started = fixture.startAndAwait(flow);
            String executionId = started.id();
            String flowKey = flow.key();
            fixture.restartServer();
            Flow persisted = fixture.flowService().latestFlow(
                fixture.session(), flowKey
            ).orElseThrow();
            Execution persistedExecution = query(fixture, executionId);
            PausedTaskRunRef pause = fixture.waitingForExecution(executionId);

            Execution completed = fixture.resume(
                pause, Map.of("decision", "APPROVED")
            );
            assertEquals(flowKey, persisted.key());
            assertEquals(State.Type.PAUSED, persistedExecution.state().current());
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class ThrowingTask extends Task implements RunnableTask {

        @Override
        public RunResult run(RunContext context) {
            throw new IllegalStateException("uc04-downstream-failure");
        }
    }

    private static void assertPausedAndUnchanged(
        WorkflowUcFixture fixture,
        String executionId,
        PausedTaskRunRef pause,
        int taskRunCount
    ) {
        Execution execution = query(fixture, executionId);
        assertEquals(State.Type.PAUSED, execution.state().current());
        assertEquals(taskRunCount, execution.taskRuns().size());
        assertEquals(State.Type.PAUSED,
            execution.requireTaskRun(pause.taskRunId()).state().current());
        assertEquals(Map.of(), execution.requireTaskRun(pause.taskRunId())
            .outputs());
    }

    private static Execution query(
        WorkflowUcFixture fixture,
        String executionId
    ) {
        return fixture.executionService().execution(
            fixture.session(), executionId
        ).orElseThrow();
    }

    private static Task task(Flow flow, String key) {
        return flow.allTasks().stream()
            .filter(candidate -> candidate.key().equals(key))
            .findFirst()
            .orElseThrow();
    }

    private static TaskRun run(Execution execution, Task task) {
        return execution.taskRunsForTask(task.id()).stream()
            .findFirst()
            .orElseThrow();
    }

    private static String pauseFlowYaml(
        String key,
        boolean required,
        boolean trailing
    ) {
        String requiredLine = required ? "        required: true\n" : "";
        String trailingTasks = trailing ? "" : "";
        return """
            key: %s
            description: standard Pause user flow
            tasks:
              - key: wait-confirmation
                type: org.cses.flow.extensions.flow.Pause
                pause:
                  key: create-confirmation
                  type: org.cses.flow.extensions.log.Log
                  message: "test step"
                resume:
                  - key: decision
                    type: STRING
            %s
                outputs:
                  - key: decision
                    type: STRING
              - key: record-result
                type: org.cses.flow.extensions.log.Log
                message: "test step"
            """.formatted(key, requiredLine + trailingTasks);
    }
}
