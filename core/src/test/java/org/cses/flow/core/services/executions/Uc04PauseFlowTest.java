package org.cses.flow.core.services.executions;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.Generation;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.cses.flow.core.services.executions.WorkflowUcFixture.PausedTaskRunRef;
import static org.cses.flow.core.services.executions.WorkflowUcFixture.RewindAttempt;

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

            assertTrue(started.generation().current().isEmpty());
            assertTrue(started.generation().history().currents().isEmpty());

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

    @Test
    void s10UserRewindsFromPauseToHistoricalStepAndContinues() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(rewindFragmentFlowYaml(
                "uc04-s10-flow"
            ));
            Execution started = fixture.startAndAwait(flow);
            Task untouchedTask = task(flow, "untouched-before");
            Task targetTask = task(flow, "recompute");
            Task sequenceTask = task(flow, "nested-fragment");
            Task realChildTask = task(flow, "real-child");
            Task skippedRouteTask = task(flow, "skipped-path");
            Task skippedChildTask = task(flow, "never-created-child");
            Task pauseTask = task(flow, "wait-confirmation");
            Task pauseChildTask = task(flow, "create-confirmation");
            Task afterTask = task(flow, "record-result");
            TaskRun untouched = onlyRun(started, untouchedTask);
            TaskRun target = onlyRun(started, targetTask);
            PausedTaskRunRef source = fixture.waitingForExecution(started.id());
            TaskRun pauseChild = onlyRun(started, pauseChildTask);
            TaskRun skippedRoute = onlyRun(started, skippedRouteTask);
            String reason = "refresh approval inputs";
            List<String> expectedAffectedTaskRunIds = List.of(
                pauseChild.id(),
                source.taskRunId(),
                skippedRoute.id(),
                onlyRun(started, realChildTask).id(),
                onlyRun(started, sequenceTask).id(),
                target.id()
            );

            assertTrue(started.generation().current().isEmpty());
            assertTrue(started.generation().history().currents().isEmpty());
            assertEquals(State.Type.SKIPPED,
                skippedRoute.state().current());
            assertNoRun(started, skippedChildTask);
            assertNoRun(started, afterTask);

            RewindAttempt rewind = fixture.rewind(
                source,
                target.id(),
                reason
            );
            RewindResult accepted = rewind.accepted();
            Execution acceptedSnapshot = accepted.execution();
            Execution rewound = rewind.rewound();
            Generation.Current current = rewound.generation().current()
                .orElseThrow();
            PausedTaskRunRef rerunPause = fixture.waitingForExecution(
                started.id()
            );
            TaskRun rerunTarget = latestRun(rewound, targetTask);

            // S10 预期：受理结果保留退回前快照并给出完整回滚顺序。
            assertExecutionSnapshot(acceptedSnapshot, started);
            assertEquals(
                expectedAffectedTaskRunIds,
                accepted.affectedTaskRunIds()
            );
            assertEquals(State.Type.PAUSED,
                acceptedSnapshot.state().current());
            assertTrue(acceptedSnapshot.generation().current().isEmpty());

            // S10 预期：当前片段公开版本、源、目标和用户原因。
            assertGeneration(
                current,
                1,
                source.taskRunId(),
                target.id(),
                reason
            );
            assertTrue(rewound.generation().history().currents().isEmpty());

            // S10 预期：只重跑目标到源 Pause，片段外步骤不提前重复。
            assertEquals(1, rewound.taskRunsForTask(untouchedTask.id()).size());
            assertEquals(untouched.id(), onlyRun(rewound, untouchedTask).id());
            assertEquals(2, rewound.taskRunsForTask(targetTask.id()).size());
            assertEquals(2, rewound.taskRunsForTask(sequenceTask.id()).size());
            assertEquals(2, rewound.taskRunsForTask(realChildTask.id()).size());
            assertEquals(2,
                rewound.taskRunsForTask(skippedRouteTask.id()).size());
            assertEquals(2, rewound.taskRunsForTask(pauseTask.id()).size());
            assertNoRun(rewound, skippedChildTask);
            assertNoRun(rewound, afterTask);
            assertNotEquals(source.taskRunId(), rerunPause.taskRunId());
            assertNotEquals(target.id(), rerunTarget.id());
            assertEquals(1,
                rerunTarget.executionGenerationVersion().orElseThrow());

            // 持久化往返：重建服务后仍可从公开查询恢复当前片段。
            fixture.restartServer();
            Execution persistedCurrent = query(fixture, started.id());
            assertGeneration(
                persistedCurrent.generation().current().orElseThrow(),
                1,
                source.taskRunId(),
                target.id(),
                reason
            );
            PausedTaskRunRef requeriedPause = fixture.waitingForExecution(
                started.id()
            );
            assertEquals(rerunPause.taskRunId(), requeriedPause.taskRunId());

            Execution completed = fixture.resume(
                requeriedPause,
                Map.of("decision", "APPROVED")
            );
            Execution reloaded = query(fixture, started.id());
            Generation completedGeneration = reloaded.generation();

            // S10 场景结束：当前片段清空，历史保留且下游使用重跑结果。
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertTrue(completedGeneration.current().isEmpty());
            assertEquals(1, completedGeneration.history().currents().size());
            assertGeneration(
                completedGeneration.history().currents().getFirst(),
                1,
                source.taskRunId(),
                target.id(),
                reason
            );
            assertEquals(1, reloaded.taskRunsForTask(afterTask.id()).size());
            assertEquals(
                rerunTarget.outputs().get("token"),
                inputOutput(onlyRun(reloaded, afterTask), "recompute", "token")
            );
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void s11UserRewindsAgainFromTheNewPause() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(rewindFlowYaml("uc04-s11-flow"));
            Execution started = fixture.startAndAwait(flow);
            Task untouchedTask = task(flow, "untouched-before");
            Task targetTask = task(flow, "recompute");
            Task pauseTask = task(flow, "wait-confirmation");
            Task pauseChildTask = task(flow, "create-confirmation");
            Task afterTask = task(flow, "record-result");
            TaskRun firstTarget = onlyRun(started, targetTask);
            TaskRun firstPauseChild = onlyRun(started, pauseChildTask);
            PausedTaskRunRef firstSource = fixture.waitingForExecution(
                started.id()
            );
            String firstReason = "first correction";

            RewindAttempt firstAttempt = fixture.rewind(
                firstSource,
                firstTarget.id(),
                firstReason
            );
            RewindResult firstAccepted = firstAttempt.accepted();
            Execution firstRewind = firstAttempt.rewound();
            List<String> firstAffectedTaskRunIds = List.of(
                firstPauseChild.id(),
                firstSource.taskRunId(),
                firstTarget.id()
            );
            assertExecutionSnapshot(firstAccepted.execution(), started);
            assertEquals(
                firstAffectedTaskRunIds,
                firstAccepted.affectedTaskRunIds()
            );
            PausedTaskRunRef secondSource = fixture.waitingForExecution(
                started.id()
            );
            TaskRun secondTarget = latestRun(firstRewind, targetTask);
            TaskRun secondPauseChild = latestRun(
                firstRewind,
                pauseChildTask
            );
            assertGeneration(
                firstRewind.generation().current().orElseThrow(),
                1,
                firstSource.taskRunId(),
                firstTarget.id(),
                firstReason
            );

            String secondReason = "second correction";
            RewindAttempt secondAttempt = fixture.rewind(
                secondSource,
                secondTarget.id(),
                secondReason
            );
            RewindResult secondAccepted = secondAttempt.accepted();
            Execution secondRewind = secondAttempt.rewound();
            List<String> secondAffectedTaskRunIds = List.of(
                secondPauseChild.id(),
                secondSource.taskRunId(),
                secondTarget.id()
            );
            assertExecutionSnapshot(
                secondAccepted.execution(),
                firstRewind
            );
            assertEquals(
                secondAffectedTaskRunIds,
                secondAccepted.affectedTaskRunIds()
            );
            assertTrue(secondAffectedTaskRunIds.stream()
                .noneMatch(firstAffectedTaskRunIds::contains));
            PausedTaskRunRef thirdSource = fixture.waitingForExecution(
                started.id()
            );
            TaskRun thirdTarget = latestRun(secondRewind, targetTask);
            Generation active = secondRewind.generation();

            // S11 预期：上一片段进入历史，新片段使用新源、新目标和递增版本。
            assertEquals(1, active.history().currents().size());
            assertGeneration(
                active.history().currents().getFirst(),
                1,
                firstSource.taskRunId(),
                firstTarget.id(),
                firstReason
            );
            assertGeneration(
                active.current().orElseThrow(),
                2,
                secondSource.taskRunId(),
                secondTarget.id(),
                secondReason
            );
            assertNotEquals(secondSource.taskRunId(), thirdSource.taskRunId());

            // S11 预期：每次仍只重跑所选片段，片段外步骤保持一次。
            assertEquals(1,
                secondRewind.taskRunsForTask(untouchedTask.id()).size());
            assertEquals(3,
                secondRewind.taskRunsForTask(targetTask.id()).size());
            assertEquals(3,
                secondRewind.taskRunsForTask(pauseTask.id()).size());
            assertNoRun(secondRewind, afterTask);
            assertEquals(
                java.util.Arrays.asList(null, 1, 2),
                secondRewind.taskRunsForTask(targetTask.id()).stream()
                    .map(run -> run.executionGenerationVersion().isPresent()
                        ? run.executionGenerationVersion().getAsInt()
                        : null)
                    .toList()
            );

            Execution completed = fixture.resume(
                thirdSource,
                Map.of("decision", "APPROVED")
            );
            fixture.restartServer();
            Execution persisted = query(fixture, started.id());
            Generation history = persisted.generation();

            // S11 场景结束：最新结果被消费，两次片段均完成并持久化。
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(State.Type.SUCCESS, persisted.state().current());
            assertTrue(history.current().isEmpty());
            assertEquals(2, history.history().currents().size());
            assertGeneration(
                history.history().currents().getFirst(),
                1,
                firstSource.taskRunId(),
                firstTarget.id(),
                firstReason
            );
            assertGeneration(
                history.history().currents().getLast(),
                2,
                secondSource.taskRunId(),
                secondTarget.id(),
                secondReason
            );
            assertEquals(
                thirdTarget.outputs().get("token"),
                inputOutput(onlyRun(persisted, afterTask), "recompute", "token")
            );
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

    @Plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class RewindProbeTask extends Task implements RunnableTask {

        @Override
        public RunResult run(RunContext context) {
            return RunResult.success(Map.of(
                "token",
                context.taskRunInfo().id()
            ));
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

    private static TaskRun onlyRun(Execution execution, Task task) {
        java.util.List<TaskRun> runs = execution.taskRunsForTask(task.id());
        assertEquals(1, runs.size());
        return runs.getFirst();
    }

    private static TaskRun latestRun(Execution execution, Task task) {
        return execution.taskRunsForTask(task.id()).getLast();
    }

    private static void assertNoRun(Execution execution, Task task) {
        assertTrue(execution.taskRunsForTask(task.id()).isEmpty());
    }

    private static void assertExecutionSnapshot(
        Execution snapshot,
        Execution observedAtSubmission
    ) {
        assertEquals(observedAtSubmission.id(), snapshot.id());
        assertEquals(observedAtSubmission.state().current(),
            snapshot.state().current());
        assertEquals(observedAtSubmission.state().history(),
            snapshot.state().history());
        assertEquals(observedAtSubmission.generation().current(),
            snapshot.generation().current());
        assertEquals(
            observedAtSubmission.generation().history().currents(),
            snapshot.generation().history().currents()
        );
        assertEquals(
            observedAtSubmission.taskRuns().stream().map(TaskRun::id).toList(),
            snapshot.taskRuns().stream().map(TaskRun::id).toList()
        );
    }

    private static void assertGeneration(
        Generation.Current current,
        int version,
        String sourceTaskRunId,
        String targetTaskRunId,
        String reason
    ) {
        assertEquals(version, current.version());
        assertEquals(sourceTaskRunId,
            current.sourceTaskRunId().orElseThrow());
        assertEquals(targetTaskRunId,
            current.targetTaskRunId().orElseThrow());
        assertEquals(reason, current.reason());
    }

    private static Object inputOutput(
        TaskRun taskRun,
        String taskKey,
        String outputKey
    ) {
        Map<?, ?> outputs = (Map<?, ?>) taskRun.inputs().get("outputs");
        Map<?, ?> taskOutputs = (Map<?, ?>) outputs.get(taskKey);
        return taskOutputs.get(outputKey);
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

    private static String rewindFlowYaml(String key) {
        return """
            key: %s
            description: rewind a serial fragment from Pause
            tasks:
              - key: untouched-before
                type: %s
                outputs:
                  - key: token
                    type: STRING
              - key: recompute
                type: %s
                outputs:
                  - key: token
                    type: STRING
              - key: wait-confirmation
                type: org.cses.flow.extensions.flow.Pause
                pause:
                  key: create-confirmation
                  type: org.cses.flow.extensions.log.Log
                  message: "test step"
                resume:
                  - key: decision
                    type: STRING
                outputs:
                  - key: decision
                    type: STRING
              - key: record-result
                type: org.cses.flow.extensions.log.Log
                message: "test step"
            """.formatted(
                key,
                RewindProbeTask.class.getCanonicalName(),
                RewindProbeTask.class.getCanonicalName()
            );
    }

    private static String rewindFragmentFlowYaml(String key) {
        return """
            key: %s
            description: rewind a fragment with real and skipped children
            tasks:
              - key: untouched-before
                type: %s
                outputs:
                  - key: token
                    type: STRING
              - key: recompute
                type: %s
                outputs:
                  - key: token
                    type: STRING
              - key: nested-fragment
                type: org.cses.flow.extensions.flow.Sequence
                tasks:
                  - key: real-child
                    type: %s
                    outputs:
                      - key: token
                        type: STRING
              - key: skipped-path
                type: org.cses.flow.extensions.flow.Route
                route: '{{ outputs.recompute.token }} == NEVER_MATCHES'
                tasks:
                  - key: never-created-child
                    type: org.cses.flow.extensions.log.Log
                    message: "must not run"
              - key: wait-confirmation
                type: org.cses.flow.extensions.flow.Pause
                pause:
                  key: create-confirmation
                  type: org.cses.flow.extensions.log.Log
                  message: "test step"
                resume:
                  - key: decision
                    type: STRING
                outputs:
                  - key: decision
                    type: STRING
              - key: record-result
                type: org.cses.flow.extensions.log.Log
                message: "test step"
            """.formatted(
                key,
                RewindProbeTask.class.getCanonicalName(),
                RewindProbeTask.class.getCanonicalName(),
                RewindProbeTask.class.getCanonicalName()
            );
    }
}
