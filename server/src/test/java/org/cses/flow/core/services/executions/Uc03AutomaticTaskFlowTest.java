package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.ExecutionStatus;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.executions.TaskRunStatus;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowStatus;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.domains.tasks.TaskPlugin;
import org.cses.flow.core.domains.tasks.RouteExpression;
import org.cses.flow.worker.WorkerContext;
import org.cses.flow.worker.WorkerTaskHandler;
import org.cses.flow.worker.WorkerTaskResult;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC: docs/uc/flow/UC-03 自动 Task 流程.md
 */
class Uc03AutomaticTaskFlowTest {

    @Test
    void s1CompletesAutomaticTasksInDefinitionOrder() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.publish("""
                key: uc03-s1-flow
                description: 自动处理 Flow
                tasks:
                  - key: 初始化数据
                    type: AUTO
                  - key: 执行处理
                    type: AUTO
                  - key: 完成处理
                    type: AUTO
                """);

            Execution execution = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );

            assertEquals(FlowStatus.DEPLOYED, flow.status());
            assertEquals(1L, flow.reversion());
            assertFalse(execution.id().isBlank());
            assertEquals(flow.id(), execution.flowId());
            assertEquals(1L, execution.flowReversion());
            // PASS-S1-01
            assertEquals(ExecutionStatus.COMPLETED, execution.status());
            assertEquals(
                List.of("初始化数据", "执行处理", "完成处理"),
                flow.tasks().stream().map(Task::key).toList()
            );
            // PASS-S1-03
            assertEquals(
                flow.tasks().stream().map(Task::id).toList(),
                execution.taskRuns().stream()
                    .map(TaskRun::taskId)
                    .toList()
            );
            // PASS-S1-02
            assertEquals(
                List.of(
                    TaskRunStatus.COMPLETED,
                    TaskRunStatus.COMPLETED,
                    TaskRunStatus.COMPLETED
                ),
                execution.taskRuns().stream()
                    .map(TaskRun::status)
                    .toList()
            );
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
            assertEquals(
                flow,
                fixture.flowService().flow(
                    fixture.session(),
                    flow.id(),
                    1L,
                    FlowStatus.DEPLOYED
                ).orElseThrow()
            );
            assertTrue(
                fixture.flowService().flow(
                    fixture.session(),
                    flow.id(),
                    null,
                    FlowStatus.DRAFT
                ).isEmpty()
            );
        }
    }

    @Test
    void s2KeepsTwoExecutionsAndTheirTaskRunsIsolated() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.publish("""
                key: uc03-s2-flow
                description: 多实例自动处理 Flow
                tasks:
                  - key: first
                    type: AUTO
                  - key: second
                    type: AUTO
                  - key: third
                    type: AUTO
                """);

            Execution first = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            Execution second = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );

            // PASS-S2-01
            assertNotEquals(first.id(), second.id());
            assertTrue(first.taskRuns().stream().map(TaskRun::id)
                .noneMatch(second.taskRuns().stream()
                    .map(TaskRun::id)
                    .collect(java.util.stream.Collectors.toSet())::contains));
            // PASS-S2-02
            assertEquals(3, first.taskRuns().size());
            assertEquals(3, second.taskRuns().size());
            assertEquals(
                flow.tasks().stream().map(Task::id).toList(),
                first.taskRuns().stream().map(TaskRun::taskId).toList()
            );
            assertEquals(
                flow.tasks().stream().map(Task::id).toList(),
                second.taskRuns().stream().map(TaskRun::taskId).toList()
            );
            assertEquals(ExecutionStatus.COMPLETED, first.status());
            assertEquals(ExecutionStatus.COMPLETED, second.status());
        }
    }

    @Test
    void s3ExplicitWorkerFailureStopsFollowingTasks() {
        try (WorkflowUcFixture fixture =
                 WorkflowUcFixture.openWithSingletons(
                     new TestTaskPlugin("TEST_FAIL"),
                     new TestTaskHandler()
                 )) {
            Flow flow = fixture.publish("""
                key: uc03-s3-flow
                description: explicit worker failure
                tasks:
                  - key: first
                    type: AUTO
                  - key: fail
                    type: TEST_FAIL
                  - key: never-run
                    type: AUTO
                """);

            Execution execution = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );

            TaskRun failed = execution.taskRuns().get(1);
            // PASS-S3-01
            assertEquals(TaskRunStatus.FAILED, failed.status());
            assertEquals("uc03-explicit-failure", failed.error());
            // PASS-S3-02
            assertEquals(ExecutionStatus.FAILED, execution.status());
            assertEquals(2, execution.taskRuns().size());
            assertEquals(
                List.of("first", "fail"),
                execution.taskRuns().stream()
                    .map(run -> flow.tasks().stream()
                        .filter(task -> task.id().equals(run.taskId()))
                        .findFirst().orElseThrow().key())
                    .toList()
            );
        }
    }

    @Test
    void s4UnhandledWorkerFailureRollsBackCreatedExecution() {
        try (WorkflowUcFixture fixture =
                 WorkflowUcFixture.openWithSingletons(
                     new TestTaskPlugin("TEST_THROW"),
                     new TestTaskHandler()
                 )) {
            Flow flow = fixture.publish("""
                key: uc03-s4-flow
                description: unhandled worker failure
                tasks:
                  - key: first
                    type: AUTO
                  - key: explode
                    type: TEST_THROW
                """);
            long before = fixture.executionCount();

            // PASS-S4-01
            IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> fixture.executionService().create(
                    fixture.session(),
                    flow.id()
                )
            );
            assertEquals("uc03-unhandled-failure", failure.getMessage());
            // PASS-S4-02
            assertEquals(before, fixture.executionCount());
        }
    }

    @Test
    void s5FreshServerStartsPersistedDefinitionByTaskIdValue() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.publish("""
                key: uc03-s5-flow
                description: persistence value equality
                tasks:
                  - key: first
                    type: AUTO
                  - key: second
                    type: AUTO
                  - key: third
                    type: AUTO
                """);

            fixture.restartServer();
            Execution completed = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );

            // PASS-S5-01
            assertEquals(
                flow.tasks().stream().map(Task::id).toList(),
                completed.taskRuns().stream()
                    .map(TaskRun::taskId)
                    .toList()
            );
            // PASS-S5-02
            assertEquals(3, completed.taskRuns().size());
            assertEquals(ExecutionStatus.COMPLETED, completed.status());
            assertTrue(completed.taskRuns().stream()
                .allMatch(run -> run.status() == TaskRunStatus.COMPLETED));
        }
    }

    @Test
    void s6CancelCompletedExecutionIsRejectedWithoutMutation() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.publish("""
                key: uc03-s6-flow
                description: terminal idempotency
                tasks:
                  - key: first
                    type: AUTO
                  - key: second
                    type: AUTO
                """);
            Execution completed = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            List<String> taskRunIds = completed.taskRuns().stream()
                .map(TaskRun::id)
                .toList();
            long lockVersion = completed.lockVersion();

            // PASS-S6-01
            assertThrows(
                org.cses.flow.core.exceptions.shared.WorkflowException.class,
                () -> fixture.executionService().cancel(
                    fixture.session(),
                    completed.id()
                )
            );

            Execution reloaded = fixture.executionService().execution(
                fixture.session(),
                completed.id()
            ).orElseThrow();
            // PASS-S6-02
            assertEquals(
                taskRunIds,
                reloaded.taskRuns().stream().map(TaskRun::id).toList()
            );
            assertEquals(2, reloaded.taskRuns().size());
            assertEquals(ExecutionStatus.COMPLETED, reloaded.status());
            assertEquals(lockVersion, reloaded.lockVersion());
            assertEquals(
                completed.taskRuns().stream().map(TaskRun::status).toList(),
                reloaded.taskRuns().stream().map(TaskRun::status).toList()
            );
        }
    }

    private static final class TestTask extends Task {

        private TestTask(
            String id,
            String parentId,
            String key,
            String type,
            List<? extends Input> inputs,
            List<? extends Output> outputs,
            RouteExpression route,
            List<String> dependOn,
            List<? extends Task> tasks
        ) {
            super(
                id,
                parentId,
                key,
                type,
                inputs,
                outputs,
                route,
                dependOn,
                tasks
            );
        }

        private static TestTask create(
            String id,
            String parentId,
            String key,
            String type,
            List<? extends Input> inputs,
            List<? extends Output> outputs,
            RouteExpression route,
            List<String> dependOn,
            List<? extends Task> tasks
        ) {
            return new TestTask(
                id,
                parentId,
                key,
                type,
                inputs,
                outputs,
                route,
                dependOn,
                tasks
            );
        }

        private static TestTask rehydrate(
            String id,
            String parentId,
            String key,
            String type,
            List<? extends Input> inputs,
            List<? extends Output> outputs,
            RouteExpression route,
            List<String> dependOn,
            List<? extends Task> tasks
        ) {
            return new TestTask(
                id,
                parentId,
                key,
                type,
                inputs,
                outputs,
                route,
                dependOn,
                tasks
            );
        }
    }

    private static final class TestTaskPlugin implements TaskPlugin {

        private final String type;

        private TestTaskPlugin(String type) {
            this.type = type;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public Task create(
            String id,
            String parentId,
            String key,
            List<Input> inputs,
            List<Output> outputs,
            RouteExpression route,
            List<String> dependOn,
            Map<String, ?> properties,
            List<? extends Task> tasks
        ) {
            return TestTask.create(
                id,
                parentId,
                key,
                type,
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
            List<Input> inputs,
            List<Output> outputs,
            RouteExpression route,
            List<String> dependOn,
            Map<String, ?> properties,
            List<? extends Task> tasks
        ) {
            return TestTask.rehydrate(
                id,
                parentId,
                key,
                type,
                inputs,
                outputs,
                route,
                dependOn,
                tasks
            );
        }

        @Override
        public Map<String, Object> properties(Task task) {
            if (!(task instanceof TestTask)) {
                throw new IllegalArgumentException(
                    "Test plugin requires TestTask"
                );
            }
            return Map.of();
        }
    }

    private static final class TestTaskHandler
        implements WorkerTaskHandler {

        @Override
        public boolean supports(Task task) {
            return task instanceof TestTask;
        }

        @Override
        public <S extends Session<U>, U extends User>
            WorkerTaskResult execute(WorkerContext<S, U> context) {

            if ("TEST_FAIL".equals(context.workerTask().task().type())) {
                return WorkerTaskResult.failed(
                    context.workerTask(),
                    "uc03-explicit-failure"
                );
            }
            throw new IllegalStateException("uc03-unhandled-failure");
        }
    }
}
