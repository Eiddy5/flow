package org.cses.flow.worker;

import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.expressions.TemplateExpression;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.VoidOutput;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.cses.flow.core.plugins.TestNotificationTask;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.extensions.flow.Pause;
import org.cses.flow.extensions.log.Log;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerDispatcherTest {

    private static Context PLUGINS = builtInContext(
        new TestNotificationTask()
    );
    private WorkerDispatcher dispatcher = new WorkerDispatcher();

    /** 初始化无 Micronaut 容器的 PAAS JSON 序列化边界。 */
    @org.junit.jupiter.api.BeforeEach
    void initializeJson() {
        org.paas.json.JsonFactory.instance = io.micronaut.json.JsonMapper.createDefault();
    }

    @Test
    void directlyInvokesTheRunnableTask() {
        Log task = Log.builder()
            .id("task-1")
            .key("log")
            .message(TemplateExpression.parse("test step"))
            .build();
        WorkerTask workerTask = workerTask(
            "execution-1",
            "task-run-1",
            null,
            task,
            Map.of("payload", "value")
        );

        WorkerTaskResult result = dispatcher.dispatch(workerTask);

        assertEquals(State.Type.SUCCESS, result.targetState());
        assertEquals(Map.of(), result.outputs());
    }

    /** 具体 Output 和传输信封均拒绝 SKIPPED 作为 Worker 结果。 */
    @Test
    void runnableAndWorkerResultsRejectSkippedAsAWorkerOutcome() {
        Log task = Log.builder().id("task-1").key("log").build();
        WorkerTask worker = workerTask("execution-1", "task-run-1", null, task, Map.of());
        assertThrows(IllegalArgumentException.class,
            () -> WorkerTaskResult.from(worker, StatusOutput.from(State.Type.SKIPPED, null)));
        assertThrows(
            IllegalArgumentException.class,
            () -> WorkerTaskResult.from(
                "execution-1",
                "task-run-1",
                State.Type.SKIPPED,
                Map.of(),
                null
            )
        );
    }

    @Test
    void propagatesUnexpectedTaskException() {
        FailingTask task = FailingTask.builder()
            .id("task-1")
            .key("failing")
            .build();

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> dispatcher.dispatch(workerTask(
                "execution-1",
                "task-run-1",
                null,
                task,
                Map.of()
            ))
        );

        assertEquals("unexpected-task-failure", exception.getMessage());
    }

    @Test
    void rejectsOrchestrationTasksBeforeWorkerDispatch() {
        Pause task = Pause.builder()
            .id("task-1")
            .key("pause")
            .onPause(Log.builder()
                .id("action-1")
                .key("create-pause")
                .message(TemplateExpression.parse("test step"))
                .build())
            .build();

        assertThrows(
            IllegalArgumentException.class,
            () -> WorkerTask.from(
                "execution-1",
                "task-run-1",
                task,
                variables(
                    "execution-1",
                    "task-run-1",
                    null,
                    task,
                    Map.of()
                )
            )
        );
    }

    @Test
    void dispatchesAMaterializedPluginUsingItsCustomField() {
        Flow flow = PLUGINS.deploy(
            "worker-company",
            "worker-flow",
            Map.of(
                "key", "notification-flow",
                "tasks", List.of(Map.of(
                    "key", "notify",
                    "type",
                    TestNotificationTask.class.getCanonicalName(),
                    "channel", "operations"
                ))
            ),
            null,
            ActorRef.create("worker-user", "Worker User"),
            1_785_312_000_000L
        );
        TestNotificationTask task = assertInstanceOf(
            TestNotificationTask.class,
            flow.tasks().getFirst()
        );

        WorkerTaskResult result = dispatcher.dispatch(workerTask(
            "execution-1",
            "task-run-1",
            null,
            task,
            Map.of()
        ));

        assertEquals(
            Map.of("channel", "operations"),
            result.outputs()
        );
    }

    @Test
    void createsOneFreshRunContextPerRunnableInvocation() {
        ContextRecordingTask task = ContextRecordingTask.builder()
            .id("task-1")
            .key("record-context")
            .build();
        Map<String, Object> sourceInputs = new LinkedHashMap<>();
        sourceInputs.put("payload", "original");
        WorkerTask firstWorkerTask = workerTask(
            "execution-1",
            "task-run-1",
            "parent-run-1",
            task,
            sourceInputs
        );
        WorkerTask secondWorkerTask = workerTask(
            "execution-1",
            "task-run-2",
            null,
            task,
            sourceInputs
        );
        sourceInputs.put("payload", "changed");

        WorkerTaskResult first = dispatcher.dispatch(firstWorkerTask);
        dispatcher.dispatch(secondWorkerTask);

        assertEquals(Map.of("payload", "original"), first.outputs());
        assertEquals(2, task.contexts.size());
        assertNotSame(task.contexts.get(0), task.contexts.get(1));
        assertEquals(
            "execution-1",
            task.contexts.getFirst().taskRunInfo().executionId()
        );
        assertEquals(
            "task-run-1",
            task.contexts.getFirst().taskRunInfo().id()
        );
        assertEquals("task-run-2", task.contexts.get(1).taskRunInfo().id());
        assertEquals(
            "task-1",
            task.contexts.getFirst().taskRunInfo().taskId()
        );
        assertEquals(
            "record-context",
            task.contexts.getFirst().taskRunInfo().taskKey()
        );
        assertEquals(
            "parent-run-1",
            task.contexts.getFirst().parentTaskRunId().orElseThrow()
        );
        assertTrue(task.contexts.get(1).parentTaskRunId().isEmpty());
        assertEquals(
            "execution-1",
            map(task.contexts.getFirst().variables(), "execution").get("id")
        );
        assertEquals(
            "task-run-1",
            map(task.contexts.getFirst().variables(), "taskRun").get("id")
        );
        assertEquals(
            Map.of("payload", "original"),
            task.contexts.getFirst().taskInputs()
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> task.contexts.getFirst().variables().put("custom", "value")
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> task.contexts.getFirst().taskInputs().put(
                "payload",
                "mutated"
            )
        );
    }

    @Test
    void rejectsVariableIdentitiesThatDoNotMatchTheEnvelope() {
        Log task = Log.builder()
            .id("task-1")
            .key("log")
            .message(TemplateExpression.parse("test step"))
            .build();

        IllegalArgumentException taskRunFailure = assertThrows(
            IllegalArgumentException.class,
            () -> WorkerTask.from(
                "execution-1",
                "task-run-1",
                task,
                variables(
                    "execution-1",
                    "spoofed-task-run",
                    null,
                    task,
                    Map.of()
                )
            )
        );
        assertTrue(taskRunFailure.getMessage().contains(
            "spoofed-task-run != task-run-1"
        ));

        assertThrows(
            IllegalArgumentException.class,
            () -> WorkerTask.from(
                "execution-1",
                "task-run-1",
                task,
                variables(
                    "execution-1",
                    "task-run-1",
                    "spoofed-parent-run",
                    task,
                    Map.of()
                )
            )
        );
    }

    @Test
    void rejectsAnExecutionIdentityThatDoesNotMatchTheEnvelope() {
        Log task = Log.builder()
            .id("task-1")
            .key("log")
            .message(TemplateExpression.parse("test step"))
            .build();

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> WorkerTask.from(
                "execution-1",
                "task-run-1",
                task,
                variables(
                    "execution-2",
                    "task-run-1",
                    null,
                    task,
                    Map.of()
                )
            )
        );

        assertEquals(
            "Worker identity does not match variables: "
                + "execution-2 != execution-1",
            failure.getMessage()
        );
    }

    /** 具体业务 POJO 的字段成为结果，状态和错误仅进入运行信封。 */
    @Test
    void projectsTypedPojoFieldsAndKeepsControlMetadataOutOfOutputs() {
        Log task = Log.builder().id("task-1").key("log").build();
        WorkerTask worker = workerTask("execution-1", "task-run-1", null, task, Map.of());
        WorkerTaskResult warning = WorkerTaskResult.from(worker,
            StatusOutput.from(State.Type.WARNING, null));
        assertEquals(State.Type.WARNING, warning.targetState());
        assertEquals(Map.of("notice", "typed-result"), warning.outputs());
        WorkerTaskResult failure = WorkerTaskResult.from(worker, VoidOutput.failed("expected-failure"));
        assertEquals(State.Type.FAILED, failure.targetState());
        assertEquals("expected-failure", failure.error());
        assertEquals(Map.of(), failure.outputs());
        assertThrows(IllegalArgumentException.class,
            () -> WorkerTaskResult.from(worker, StatusOutput.from(State.Type.FAILED, null)));
        assertThrows(IllegalArgumentException.class,
            () -> WorkerTaskResult.from(worker, StatusOutput.from(State.Type.SUCCESS, "unexpected-error")));
    }

    private static WorkerTask workerTask(
        String executionId,
        String taskRunId,
        String parentTaskRunId,
        Task task,
        Map<String, ?> taskInputs
    ) {
        Map<String, Object> variables = variables(
            executionId,
            taskRunId,
            parentTaskRunId,
            task,
            taskInputs
        );
        return parentTaskRunId == null
            ? WorkerTask.from(executionId, taskRunId, task, variables)
            : WorkerTask.from(
                executionId,
                taskRunId,
                parentTaskRunId,
                task,
                variables
            );
    }

    private static Map<String, Object> variables(
        String executionId,
        String taskRunId,
        String parentTaskRunId,
        Task task,
        Map<String, ?> taskInputs
    ) {
        Map<String, Object> parent = parentTaskRunId == null
            ? Map.of()
            : Map.of(
                "task",
                Map.of("key", "parent", "type", "test"),
                "taskRun",
                Map.of(
                    "id",
                    parentTaskRunId,
                    "inputs",
                    Map.of(),
                    "outputs",
                    Map.of()
                )
            );
        return Map.of(
            "inputs", Map.of("amount", 1200),
            "outputs", Map.of(),
            "vars", Map.of(),
            "task", Map.of(
                "id", task.id(),
                "key", task.key(),
                "type", task.getType()
            ),
            "taskRun", Map.of(
                "id", taskRunId,
                "inputs", taskInputs,
                "outputs", Map.of()
            ),
            "execution", Map.of("id", executionId, "outputs", Map.of()),
            "parent", parent,
            "parents", parent.isEmpty() ? List.of() : List.of(parent)
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(
        Map<String, ?> source,
        String key
    ) {
        return (Map<String, Object>) source.get(key);
    }

    @SuperBuilder
    @NoArgsConstructor
    private static class ContextRecordingTask
        extends Task implements RunnableTask<PayloadOutput> {

        @Builder.Default
        private List<RunContext> contexts = new ArrayList<>();

        @Override
        public PayloadOutput run(RunContext context) {
            contexts.add(context);
            return PayloadOutput.from((String) context.taskInputs().get("payload"));
        }
    }

    @SuperBuilder
    @NoArgsConstructor
    private static class FailingTask
        extends Task implements RunnableTask<VoidOutput> {

        @Override
        public VoidOutput run(RunContext context) {
            throw new IllegalStateException("unexpected-task-failure");
        }
    }

    /** 当前上下文样本的业务字段。 */
    public record PayloadOutput(String payload) implements org.cses.flow.core.domains.tasks.Output {
        /**
         * 创建读取到的 payload 输出。
         * @param payload 本次调用读取到的字符串
         * @return 保存该值的新输出
         */
        public static PayloadOutput from(String payload) {
            return new PayloadOutput(payload);
        }
    }

    /** 含具体通知字段的 POJO，用于核对状态元数据不进入业务 Map。 */
    @lombok.Getter
    @lombok.Setter
    @NoArgsConstructor
    public static class StatusOutput implements org.cses.flow.core.domains.tasks.Output {
        String notice;
        @com.fasterxml.jackson.annotation.JsonIgnore
        State.Type targetState;
        @com.fasterxml.jackson.annotation.JsonIgnore
        String failure;

        /**
         * 创建指定状态的通知结果。
         * @param state 本次 Worker 状态；测试同时使用非法值检查边界
         * @param error 失败原因；成功时应为空
         * @return 包含固定 notice 的新结果
         */
        public static StatusOutput from(State.Type state, String error) {
            StatusOutput result = new StatusOutput();
            result.notice = "typed-result";
            result.targetState = state;
            result.failure = error;
            return result;
        }
        /** @return 明确请求的运行状态 */
        @Override
        public java.util.Optional<State.Type> state() {
            return java.util.Optional.ofNullable(targetState);
        }
        /** @return 明确请求的失败原因；无错误时为空 */
        @Override
        public java.util.Optional<String> error() {
            return java.util.Optional.ofNullable(failure);
        }
    }
}
