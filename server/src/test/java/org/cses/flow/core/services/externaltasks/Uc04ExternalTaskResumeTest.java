package org.cses.flow.core.services.externaltasks;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.externaltasks.ExternalTask;
import org.cses.flow.core.domains.externaltasks.ExternalTaskStatus;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.tasks.RunContext;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.domains.tasks.RouteExpression;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.services.executions.WorkflowUcFixture;
import org.cses.flow.core.plugins.TaskExtension;
import org.junit.jupiter.api.Test;
import org.paas.common.util.StringUtil;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC: docs/uc/flow/UC-04 用户处理外派任务并恢复流程.md
 */
class Uc04ExternalTaskResumeTest {

    @Test
    void s1UserQueriesAndCompletesSingleExternalTask() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "uc04-s1-flow",
                    "外部恢复 Flow",
                    true
                )
            );

            Execution started = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );

            // PASS-S1-01
            assertEquals(State.Type.WAITING, started.state().current());
            assertEquals(
                State.Type.WAITING,
                started.taskRuns().getFirst().state().current()
            );
            assertEquals(1, started.taskRuns().size());

            fixture.restartServer();
            ExternalTask externalTask =
                fixture.waitingForExecution(started.id());
            assertEquals(ExternalTaskStatus.WAITING, externalTask.status());
            assertEquals(
                started.id(),
                externalTask.executionId()
            );

            Execution completed =
                fixture.externalTaskService().complete(
                    fixture.session(),
                    externalTask.id(),
                    Map.of("decision", "APPROVED")
                );
            // PASS-S1-02
            assertEquals(State.Type.COMPLETED, completed.state().current());
            assertTrue(completed.taskRuns().stream()
                .allMatch(run ->
                    run.state().current() == State.Type.COMPLETED
                ));
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s9FreshServerQueriesAndCompletesThePersistedWaitingTask() {
        String companyId = "uc04-s9-" + StringUtil.newId();
        String executionId;
        try (WorkflowUcFixture starter =
            WorkflowUcFixture.openLeavingWaiting(companyId)) {

            Flow flow = starter.deploy(
                WorkflowUcFixture.pauseYaml(
                    "uc04-s9-flow",
                    "跨 server 持久化恢复 Flow",
                    true
                )
            );
            Execution started = starter.executionService().create(
                starter.session(),
                flow.id()
            );

            assertEquals(State.Type.WAITING, started.state().current());
            assertEquals(
                1,
                starter.externalTaskService().waitingTasks(starter.session())
                    .size()
            );
            executionId = started.id();
        }

        try (WorkflowUcFixture verifier = WorkflowUcFixture.open()) {
            Session<User> session =
                verifier.sessionForExactCompany(companyId);
            ExternalTask waiting = verifier.externalTaskService()
                .waitingTasks(session)
                .stream()
                .filter(task -> task.executionId().equals(executionId))
                .findFirst()
                .orElseThrow();
            Execution completed = verifier.externalTaskService().complete(
                session,
                waiting.id(),
                Map.of("decision", "APPROVED")
            );

            assertEquals(State.Type.COMPLETED, completed.state().current());
            assertEquals(
                List.of(
                    State.Type.COMPLETED,
                    State.Type.COMPLETED
                ),
                completed.taskRuns().stream()
                    .map(taskRun -> taskRun.state().current())
                    .toList()
            );
            assertTrue(
                verifier.externalTaskService().waitingTasks(session).isEmpty()
            );

            verifier.restartServer();
            Execution persisted = verifier.executionService().execution(
                session,
                executionId
            ).orElseThrow();
            assertEquals(State.Type.COMPLETED, persisted.state().current());
            assertEquals(2, persisted.taskRuns().size());
            assertTrue(
                verifier.externalTaskService().waitingTasks(session).isEmpty()
            );
        }
    }

    @Test
    void s2CompletesAndResumesWithoutRunningPauseWorkerAgain() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "uc04-s2-flow",
                    "外部恢复 Flow",
                    true
                )
            );
            Execution started = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            fixture.restartServer();
            ExternalTask externalTask =
                fixture.waitingForExecution(started.id());

            Execution completed =
                fixture.externalTaskService().complete(
                    fixture.session(),
                    externalTask.id(),
                    Map.of("decision", "approved")
                );

            ExternalTask completedExternalTask =
                fixture.externalTaskService().externalTask(
                    fixture.session(),
                    externalTask.id()
                ).orElseThrow();
            assertEquals(
                ExternalTaskStatus.COMPLETED,
                completedExternalTask.status()
            );
            assertEquals(
                Map.of("decision", "approved"),
                completedExternalTask.outputs()
            );
            // PASS-S2-01
            assertEquals(
                completedExternalTask.outputs(),
                completed.taskRuns().getFirst().outputs()
            );
            // PASS-S2-02
            assertEquals(
                flow.tasks().stream().map(Task::id).toList(),
                completed.taskRuns().stream().map(TaskRun::taskId).toList()
            );
            assertEquals(
                List.of(
                    State.Type.COMPLETED,
                    State.Type.COMPLETED
                ),
                completed.taskRuns().stream().map(taskRun -> taskRun.state().current()).toList()
            );
            assertEquals(started.id(), completed.id());
            assertEquals(State.Type.COMPLETED, completed.state().current());
            assertEquals(1L, completed.lockVersion());
            // 重复完成由 S4 独立覆盖。
            assertThrows(
                WorkflowException.class,
                () -> fixture.externalTaskService().complete(
                    fixture.session(),
                    externalTask.id(),
                    Map.of("decision", "approved")
                )
            );
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s3RejectsInvalidInputWithoutChangingWaitingState() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "uc04-s3-flow",
                    "非法输入恢复 Flow",
                    true
                )
            );
            Execution started = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            fixture.restartServer();
            ExternalTask externalTask =
                fixture.waitingForExecution(started.id());

            // PASS-S3-01
            assertThrows(
                IllegalArgumentException.class,
                () -> fixture.externalTaskService().complete(
                    fixture.session(),
                    " ",
                    Map.of()
                )
            );
            assertThrows(
                WorkflowException.class,
                () -> fixture.externalTaskService().complete(
                    fixture.session(),
                    "missing-external-task-id",
                    Map.of()
                )
            );
            assertThrows(
                WorkflowException.class,
                () -> fixture.externalTaskService().complete(
                    fixture.session(),
                    externalTask.id(),
                    null
                )
            );

            Execution reloaded = fixture.executionService().execution(
                fixture.session(),
                started.id()
            ).orElseThrow();
            ExternalTask externalReloaded =
                fixture.externalTaskService().externalTask(
                    fixture.session(),
                    externalTask.id()
                ).orElseThrow();
            // PASS-S3-02
            assertEquals(State.Type.WAITING, reloaded.state().current());
            assertEquals(started.lockVersion(), reloaded.lockVersion());
            assertEquals(1, reloaded.taskRuns().size());
            assertEquals(
                State.Type.WAITING,
                reloaded.taskRuns().getFirst().state().current()
            );
            assertEquals(Map.of(), reloaded.taskRuns().getFirst().outputs());
            assertEquals(ExternalTaskStatus.WAITING, externalReloaded.status());
            assertEquals(
                externalTask.lockVersion(),
                externalReloaded.lockVersion()
            );
            assertEquals(Map.of(), externalReloaded.outputs());

            Execution completed =
                fixture.externalTaskService().complete(
                    fixture.session(),
                    externalTask.id(),
                    Map.of("decision", "APPROVED")
                );
            assertEquals(State.Type.COMPLETED, completed.state().current());
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s4RejectsRepeatedCompletionWithoutChangingCompletedData() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "uc04-s4-flow",
                    "重复恢复 Flow",
                    true
                )
            );
            Execution started = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            fixture.restartServer();
            ExternalTask externalTask =
                fixture.waitingForExecution(started.id());
            Execution completed =
                fixture.externalTaskService().complete(
                    fixture.session(),
                    externalTask.id(),
                    Map.of("decision", "approved")
                );
            ExternalTask completedExternal =
                fixture.externalTaskService().externalTask(
                    fixture.session(),
                    externalTask.id()
                ).orElseThrow();

            // PASS-S4-01
            assertThrows(
                WorkflowException.class,
                () -> fixture.externalTaskService().complete(
                    fixture.session(),
                    externalTask.id(),
                    Map.of("decision", "rejected")
                )
            );

            Execution reloaded = fixture.executionService().execution(
                fixture.session(),
                completed.id()
            ).orElseThrow();
            ExternalTask externalReloaded =
                fixture.externalTaskService().externalTask(
                    fixture.session(),
                    externalTask.id()
                ).orElseThrow();
            // PASS-S4-02
            assertEquals(State.Type.COMPLETED, reloaded.state().current());
            assertEquals(completed.lockVersion(), reloaded.lockVersion());
            assertEquals(2, reloaded.taskRuns().size());
            assertEquals(
                completed.taskRuns().stream().map(TaskRun::taskId).toList(),
                reloaded.taskRuns().stream().map(TaskRun::taskId).toList()
            );
            assertEquals(
                Map.of("decision", "approved"),
                reloaded.taskRuns().getFirst().outputs()
            );
            assertEquals(
                completedExternal.status(),
                externalReloaded.status()
            );
            assertEquals(
                completedExternal.outputs(),
                externalReloaded.outputs()
            );
            assertEquals(
                completedExternal.lockVersion(),
                externalReloaded.lockVersion()
            );
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s5RejectsCrossTenantReadAndCompletion() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "uc04-s5-flow",
                    "租户隔离恢复 Flow",
                    true
                )
            );
            Execution started = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            fixture.restartServer();
            ExternalTask externalTask =
                fixture.waitingForExecution(started.id());
            Session<User> otherCompany =
                fixture.sessionFor("company-2");

            // PASS-S5-01
            assertTrue(
                fixture.externalTaskService().waitingTasks(
                    otherCompany
                ).isEmpty()
            );
            assertTrue(
                fixture.externalTaskService().externalTask(
                    otherCompany,
                    externalTask.id()
                ).isEmpty()
            );
            assertThrows(
                WorkflowException.class,
                () -> fixture.externalTaskService().complete(
                    otherCompany,
                    externalTask.id(),
                    Map.of("decision", "approved")
                )
            );

            Execution completed =
                fixture.externalTaskService().complete(
                    fixture.session(),
                    externalTask.id(),
                    Map.of("decision", "APPROVED")
                );
            // PASS-S5-02
            assertEquals(
                State.Type.COMPLETED,
                completed.state().current()
            );
            assertEquals(
                ExternalTaskStatus.COMPLETED,
                fixture.externalTaskService().externalTask(
                    fixture.session(),
                    externalTask.id()
                ).orElseThrow().status()
            );
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s6ExternalTaskIdSelectsOnlyItsOwningExecution() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "uc04-s6-flow",
                    "恢复目标隔离 Flow",
                    true
                )
            );
            Execution first = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            Execution second = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            fixture.restartServer();
            ExternalTask firstExternal =
                fixture.waitingForExecution(first.id());
            ExternalTask secondExternal =
                fixture.waitingForExecution(second.id());

            Execution completed =
                fixture.externalTaskService().complete(
                    fixture.session(),
                    secondExternal.id(),
                    Map.of("decision", "approved")
                );

            // PASS-S6-01
            assertEquals(second.id(), completed.id());
            assertEquals(State.Type.COMPLETED, completed.state().current());
            // PASS-S6-02
            assertEquals(
                State.Type.WAITING,
                fixture.executionService().execution(
                    fixture.session(),
                    first.id()
                ).orElseThrow().state().current()
            );
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
            assertEquals(
                State.Type.COMPLETED,
                fixture.executionService().execution(
                    fixture.session(),
                    second.id()
                ).orElseThrow().state().current()
            );
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s7RejectsCompletionAfterExecutionCancellation() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(
                WorkflowUcFixture.pauseYaml(
                    "uc04-s7-flow",
                    "取消后恢复 Flow",
                    true
                )
            );
            Execution started = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            fixture.restartServer();
            ExternalTask externalTask =
                fixture.waitingForExecution(started.id());
            fixture.executionService().cancel(
                fixture.session(),
                started.id()
            );

            // PASS-S7-01
            assertThrows(
                WorkflowException.class,
                () -> fixture.externalTaskService().complete(
                    fixture.session(),
                    externalTask.id(),
                    Map.of("decision", "approved")
                )
            );

            Execution canceled = fixture.executionService().execution(
                fixture.session(),
                started.id()
            ).orElseThrow();
            ExternalTask externalReloaded =
                fixture.externalTaskService().externalTask(
                    fixture.session(),
                    externalTask.id()
                ).orElseThrow();
            // PASS-S7-02
            assertEquals(State.Type.TERMINATED, canceled.state().current());
            assertEquals(1, canceled.taskRuns().size());
            assertEquals(
                State.Type.TERMINATED,
                canceled.taskRuns().getFirst().state().current()
            );
            assertEquals(
                ExternalTaskStatus.CANCELED,
                externalReloaded.status()
            );
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s8ResumeFailureRollsBackExternalTaskAndExecution() {
        try (WorkflowUcFixture fixture =
                 WorkflowUcFixture.openWithSingletons(
                     new ThrowingTaskPlugin()
                 )) {
            Flow flow = fixture.deploy("""
                key: uc04-s8-flow
                description: 恢复异常回滚
                tasks:
                  - key: wait-confirmation
                    type: PAUSE
                    outputs:
                      - key: decision
                        type: STRING
                  - key: explode
                    type: TEST_THROW
                """);
            Execution started = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            fixture.restartServer();
            ExternalTask externalTask =
                fixture.waitingForExecution(started.id());

            // PASS-S8-01
            IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> fixture.externalTaskService().complete(
                    fixture.session(),
                    externalTask.id(),
                    Map.of("decision", "approved")
                )
            );
            assertEquals("uc04-s8-worker-failure", failure.getMessage());

            Execution reloaded = fixture.executionService().execution(
                fixture.session(),
                started.id()
            ).orElseThrow();
            ExternalTask externalReloaded =
                fixture.externalTaskService().externalTask(
                    fixture.session(),
                    externalTask.id()
                ).orElseThrow();
            // PASS-S8-02
            assertEquals(State.Type.WAITING, reloaded.state().current());
            assertEquals(1, reloaded.taskRuns().size());
            assertEquals(
                State.Type.WAITING,
                reloaded.taskRuns().getFirst().state().current()
            );
            assertEquals(
                ExternalTaskStatus.WAITING,
                externalReloaded.status()
            );
            assertTrue(externalReloaded.outputs().isEmpty());

            Execution canceled = fixture.executionService().cancel(
                fixture.session(),
                started.id()
            );
            assertEquals(State.Type.TERMINATED, canceled.state().current());
            assertEquals(
                State.Type.TERMINATED,
                canceled.taskRuns().getFirst().state().current()
            );
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    private static final class ThrowingTask
        extends Task implements RunnableTask {

        private ThrowingTask(
            String id,
            String parentId,
            String key,
            List<? extends Input<?>> inputs,
            List<? extends Output> outputs,
            RouteExpression route,
            List<String> dependOn,
            List<? extends Task> tasks
        ) {
            super(
                id,
                parentId,
                key,
                "TEST_THROW",
                inputs,
                outputs,
                route,
                dependOn,
                tasks
            );
        }

        private static ThrowingTask create(
            String id,
            String parentId,
            String key,
            List<? extends Input<?>> inputs,
            List<? extends Output> outputs,
            RouteExpression route,
            List<String> dependOn,
            List<? extends Task> tasks
        ) {
            return new ThrowingTask(
                id,
                parentId,
                key,
                inputs,
                outputs,
                route,
                dependOn,
                tasks
            );
        }

        private static ThrowingTask rehydrate(
            String id,
            String parentId,
            String key,
            List<? extends Input<?>> inputs,
            List<? extends Output> outputs,
            RouteExpression route,
            List<String> dependOn,
            List<? extends Task> tasks
        ) {
            return new ThrowingTask(
                id,
                parentId,
                key,
                inputs,
                outputs,
                route,
                dependOn,
                tasks
            );
        }

        @Override
        public RunResult run(RunContext context) {
            throw new IllegalStateException("uc04-s8-worker-failure");
        }
    }

    private static final class ThrowingTaskPlugin implements TaskExtension {

        private static final String TYPE = "TEST_THROW";

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public Task create(
            String id,
            String parentId,
            String key,
            List<Input<?>> inputs,
            List<Output> outputs,
            RouteExpression route,
            List<String> dependOn,
            Map<String, ?> properties,
            List<? extends Task> tasks
        ) {
            return ThrowingTask.create(
                id,
                parentId,
                key,
                inputs,
                outputs,
                route,
                dependOn,
                tasks
            );
        }

        @Override
        public Task rehydrate(
            String id,
            String parentId,
            String key,
            List<Input<?>> inputs,
            List<Output> outputs,
            RouteExpression route,
            List<String> dependOn,
            Map<String, ?> properties,
            List<? extends Task> tasks
        ) {
            return ThrowingTask.rehydrate(
                id,
                parentId,
                key,
                inputs,
                outputs,
                route,
                dependOn,
                tasks
            );
        }

        @Override
        public Map<String, Object> properties(Task task) {
            if (!(task instanceof ThrowingTask)) {
                throw new IllegalArgumentException(
                    "TEST_THROW plugin requires ThrowingTask"
                );
            }
            return Map.of();
        }
    }

}
