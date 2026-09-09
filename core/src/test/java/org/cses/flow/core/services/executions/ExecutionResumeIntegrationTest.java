package org.cses.flow.core.services.executions;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.junit.jupiter.api.Test;
import org.paas.common.util.StringUtil;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.cses.flow.core.services.executions.WorkflowUcFixture.PausedTaskRunRef;

class ExecutionResumeIntegrationTest {

    @Test
    void resumesSinglePausedTaskRun() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "execution-resume-single-flow",
                    "外部恢复 Flow",
                    true
                )
            );

            Execution started = fixture.startAndAwait(flow);

            assertEquals(State.Type.PAUSED, started.state().current());
            assertEquals(
                State.Type.PAUSED,
                started.taskRuns().getFirst().state().current()
            );
            assertEquals(2, started.taskRuns().size());

            fixture.restartServer();
            PausedTaskRunRef pausedTaskRun =
                fixture.waitingForExecution(started.id());
            assertEquals(
                State.Type.PAUSED,
                fixture.taskRun(pausedTaskRun).state().current()
            );
            assertEquals(started.id(), pausedTaskRun.executionId());

            Execution completed = fixture.resume(
                pausedTaskRun,
                Map.of("decision", "APPROVED")
            );
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertTrue(completed.taskRuns().stream()
                .allMatch(run ->
                    run.state().current() == State.Type.SUCCESS
                ));
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void freshServerResumesPersistedPausedTaskRun() {
        String companyId = "execution-resume-restart-" + StringUtil.newId();
        String executionId;
        try (WorkflowUcFixture starter =
            WorkflowUcFixture.openLeavingWaiting(companyId)) {

            Flow flow = starter.deploy(
                WorkflowUcFixture.pauseYaml(
                    "execution-resume-restart-flow",
                    "跨 server 持久化恢复 Flow",
                    true
                )
            );
            Execution started = starter.startAndAwait(flow);

            assertEquals(State.Type.PAUSED, started.state().current());
            assertEquals(
                1,
                starter.pausedTaskRuns().size()
            );
            executionId = started.id();
        }

        try (WorkflowUcFixture verifier = WorkflowUcFixture.open()) {
            Session<User> session =
                verifier.sessionForExactCompany(companyId);
            PausedTaskRunRef waiting = verifier.waitingForExecution(
                session,
                executionId
            );
            Execution completed = verifier.resume(
                session,
                waiting,
                Map.of("decision", "APPROVED")
            );

            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(
                List.of(
                    State.Type.SUCCESS,
                    State.Type.SUCCESS,
                    State.Type.SUCCESS
                ),
                completed.taskRuns().stream()
                    .map(taskRun -> taskRun.state().current())
                    .toList()
            );
            assertTrue(
                verifier.pausedTaskRuns(session).isEmpty()
            );

            verifier.restartServer();
            Execution persisted = verifier.executionService().execution(
                session,
                executionId
            ).orElseThrow();
            assertEquals(State.Type.SUCCESS, persisted.state().current());
            assertEquals(3, persisted.taskRuns().size());
            assertTrue(
                verifier.pausedTaskRuns(session).isEmpty()
            );
        }
    }

    @Test
    void resumeDoesNotRunPauseWorkerAgain() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "execution-resume-worker-once-flow",
                    "外部恢复 Flow",
                    true
                )
            );
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef pausedTaskRun =
                fixture.waitingForExecution(started.id());

            Execution completed = fixture.resume(
                pausedTaskRun,
                Map.of("decision", "approved")
            );

            TaskRun completedPause = completed.requireTaskRun(
                pausedTaskRun.taskRunId()
            );
            assertEquals(
                State.Type.SUCCESS,
                completedPause.state().current()
            );
            assertEquals(
                Map.of("decision", "approved"),
                completedPause.outputs()
            );
            assertEquals(
                completedPause.outputs(),
                completed.taskRuns().getFirst().outputs()
            );
            assertEquals(
                flow.allTasks().stream().map(Task::id).toList(),
                completed.taskRuns().stream().map(TaskRun::taskId).toList()
            );
            assertEquals(
                List.of(
                    State.Type.SUCCESS,
                    State.Type.SUCCESS,
                    State.Type.SUCCESS
                ),
                completed.taskRuns().stream().map(taskRun -> taskRun.state().current()).toList()
            );
            assertEquals(started.id(), completed.id());
            assertEquals(State.Type.SUCCESS, completed.state().current());
            // 重复完成由 S4 独立覆盖。
            assertThrows(
                WorkflowException.class,
                () -> fixture.resume(
                    pausedTaskRun,
                    Map.of("decision", "approved")
                )
            );
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void invalidOutputsKeepPausedTaskRunWaiting() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "execution-resume-invalid-outputs-flow",
                    "非法输入恢复 Flow",
                    true
                )
            );
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef pausedTaskRun =
                fixture.waitingForExecution(started.id());

            assertThrows(
                IllegalArgumentException.class,
                () -> fixture.executionService().resume(
                    fixture.session(),
                    started.id(),
                    " ",
                    Map.of()
                )
            );
            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().resume(
                    fixture.session(),
                    started.id(),
                    "missing-task-run-id",
                    Map.of()
                )
            );
            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().resume(
                    fixture.session(),
                    started.id(),
                    pausedTaskRun.taskRunId(),
                    null
                )
            );

            Execution reloaded = fixture.executionService().execution(
                fixture.session(),
                started.id()
            ).orElseThrow();
            TaskRun pauseReloaded = reloaded.requireTaskRun(
                pausedTaskRun.taskRunId()
            );
            assertEquals(State.Type.PAUSED, reloaded.state().current());
            assertEquals(2, reloaded.taskRuns().size());
            assertEquals(
                State.Type.PAUSED,
                pauseReloaded.state().current()
            );
            assertEquals(Map.of(), pauseReloaded.outputs());

            Execution completed = fixture.resume(
                pausedTaskRun,
                Map.of("decision", "APPROVED")
            );
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void repeatedResumeDoesNotChangeCompletedData() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "execution-resume-repeat-flow",
                    "重复恢复 Flow",
                    true
                )
            );
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef pausedTaskRun =
                fixture.waitingForExecution(started.id());
            Execution completed = fixture.resume(
                pausedTaskRun,
                Map.of("decision", "approved")
            );
            TaskRun completedPause = completed.requireTaskRun(
                pausedTaskRun.taskRunId()
            );

            assertThrows(
                WorkflowException.class,
                () -> fixture.resume(
                    pausedTaskRun,
                    Map.of("decision", "rejected")
                )
            );

            Execution reloaded = fixture.executionService().execution(
                fixture.session(),
                completed.id()
            ).orElseThrow();
            TaskRun pauseReloaded = reloaded.requireTaskRun(
                pausedTaskRun.taskRunId()
            );
            assertEquals(State.Type.SUCCESS, reloaded.state().current());
            assertEquals(3, reloaded.taskRuns().size());
            assertEquals(
                completed.taskRuns().stream().map(TaskRun::taskId).toList(),
                reloaded.taskRuns().stream().map(TaskRun::taskId).toList()
            );
            assertEquals(
                Map.of("decision", "approved"),
                pauseReloaded.outputs()
            );
            assertEquals(
                completedPause.state(),
                pauseReloaded.state()
            );
            assertEquals(
                completedPause.outputs(),
                pauseReloaded.outputs()
            );
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void resumeIsTenantScoped() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "execution-resume-tenant-flow",
                    "租户隔离恢复 Flow",
                    true
                )
            );
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef pausedTaskRun =
                fixture.waitingForExecution(started.id());
            Session<User> otherCompany =
                fixture.sessionFor("company-2");

            assertTrue(
                fixture.pausedTaskRuns(otherCompany).isEmpty()
            );
            assertTrue(
                fixture.executionService().execution(
                    otherCompany,
                    started.id()
                ).isEmpty()
            );
            assertThrows(
                WorkflowException.class,
                () -> fixture.executionService().resume(
                    otherCompany,
                    started.id(),
                    pausedTaskRun.taskRunId(),
                    Map.of("decision", "approved")
                )
            );

            Execution completed = fixture.resume(
                pausedTaskRun,
                Map.of("decision", "APPROVED")
            );
            assertEquals(
                State.Type.SUCCESS,
                completed.state().current()
            );
            assertEquals(
                State.Type.SUCCESS,
                completed.requireTaskRun(
                    pausedTaskRun.taskRunId()
                ).state().current()
            );
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void taskRunIdSelectsOnlyOwningExecution() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "execution-resume-select-flow",
                    "恢复目标隔离 Flow",
                    true
                )
            );
            Execution first = fixture.startAndAwait(flow);
            Execution second = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef firstPause =
                fixture.waitingForExecution(first.id());
            PausedTaskRunRef secondPause =
                fixture.waitingForExecution(second.id());

            Execution completed = fixture.resume(
                secondPause,
                Map.of("decision", "approved")
            );

            assertEquals(second.id(), completed.id());
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(
                State.Type.PAUSED,
                fixture.executionService().execution(
                    fixture.session(),
                    first.id()
                ).orElseThrow().state().current()
            );
            assertEquals(
                State.Type.PAUSED,
                fixture.taskRun(firstPause).state().current()
            );

            fixture.restartServer();
            PausedTaskRunRef remaining =
                fixture.waitingForExecution(first.id());
            assertEquals(firstPause.taskRunId(), remaining.taskRunId());
            Execution firstCompleted = fixture.resume(
                remaining,
                Map.of("decision", "APPROVED")
            );
            assertEquals(State.Type.SUCCESS, firstCompleted.state().current());
            assertEquals(
                State.Type.SUCCESS,
                fixture.executionService().execution(
                    fixture.session(),
                    second.id()
                ).orElseThrow().state().current()
            );
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void cancelledExecutionRejectsResume() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "execution-resume-cancelled-flow",
                    "取消后恢复 Flow",
                    true
                )
            );
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef pausedTaskRun =
                fixture.waitingForExecution(started.id());
            fixture.cancel(started.id());

            assertThrows(
                WorkflowException.class,
                () -> fixture.resume(
                    pausedTaskRun,
                    Map.of("decision", "approved")
                )
            );

            Execution canceled = fixture.executionService().execution(
                fixture.session(),
                started.id()
            ).orElseThrow();
            assertEquals(State.Type.KILLED, canceled.state().current());
            assertEquals(2, canceled.taskRuns().size());
            assertEquals(
                State.Type.KILLED,
                canceled.taskRuns().getFirst().state().current()
            );
            assertEquals(
                State.Type.KILLED,
                canceled.requireTaskRun(
                    pausedTaskRun.taskRunId()
                ).state().current()
            );
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Test
    void downstreamFailureMarksExecutionFailed() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy("""
                key: execution-resume-rollback-flow
                description: 恢复异常回滚
                tasks:
                  - key: wait-confirmation
                    type: org.cses.flow.extensions.flow.Pause
                    onPause:
                      key: create-confirmation
                      type: org.cses.flow.extensions.log.Log
                      message: "test step"
                    onResume:
                      - key: decision
                        type: STRING
                    outputs:
                      - key: decision
                        type: STRING
                  - key: explode
                    type: org.cses.flow.core.services.executions.ExecutionResumeIntegrationTest.ThrowingTask
                """);
            Execution started = fixture.startAndAwait(flow);
            fixture.restartServer();
            PausedTaskRunRef pausedTaskRun =
                fixture.waitingForExecution(started.id());

            Execution failed = fixture.resume(
                pausedTaskRun,
                Map.of("decision", "approved")
            );
            assertEquals(State.Type.FAILED, failed.state().current());

            Execution reloaded = fixture.executionService().execution(
                fixture.session(),
                started.id()
            ).orElseThrow();
            TaskRun pauseReloaded = reloaded.requireTaskRun(
                pausedTaskRun.taskRunId()
            );
            assertEquals(State.Type.FAILED, reloaded.state().current());
            assertEquals(3, reloaded.taskRuns().size());
            assertEquals(State.Type.SUCCESS, pauseReloaded.state().current());
            assertEquals(
                State.Type.FAILED,
                reloaded.requireTaskRun(
                    reloaded.taskRuns().getLast().id()
                ).state().current()
            );
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    @Plugin
    @SuperBuilder
    @NoArgsConstructor
    public static final class ThrowingTask
        extends Task implements RunnableTask {

        @Override
        public RunResult run(RunContext context) {
            throw new IllegalStateException(
                "execution-resume-worker-failure"
            );
        }
    }

}
