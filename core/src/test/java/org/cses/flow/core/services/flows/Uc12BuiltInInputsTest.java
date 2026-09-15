package org.cses.flow.core.services.flows;

import org.cses.flow.core.domains.flows.inputs.IntegerInput;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.expressions.TemplateExpression;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.flows.inputs.StringInput;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.services.executions.WorkflowUcFixture;
import org.cses.flow.core.services.executions.WorkflowUcFixture.PausedTaskRunRef;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.cses.flow.extensions.flow.Pause;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** UC: docs/uc/flow/UC-12 用户在 Flow 中使用内置输入类型.md */
class Uc12BuiltInInputsTest {

    /** 发布并运行三处内置输入，核对转换结果并完成流程。 */
    @Test
    void s1UserPublishesAndRunsBuiltInInputs() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow published = fixture.deploy(fullFlowYaml("uc12-s1"));
            String key = published.key();
            long version = published.version();
            fixture.restartServer();
            Flow queried = fixture.flowService().flow(fixture.session(), key, version).orElseThrow();
            assertBuiltInDefinitions(queried);

            String executionId = start(fixture, queried, Map.of("order", 41L));
            PausedTaskRunRef pause = fixture.waitingForExecution(executionId);
            Execution waiting = query(fixture, executionId);
            assertEquals(Map.of("order", 41, "reference", "builtin-default"), waiting.inputs());
            assertEquals(Map.of("value", "41|builtin-default"), run(waiting, queried, "before").outputs());

            fixture.resume(pause, Map.of("decision", 52L));
            Execution completed = assertCompleted(fixture, executionId);
            assertEquals(Map.of("decision", 52), completed.requireTaskRun(pause.taskRunId()).outputs());
            assertEquals(Map.of("value", "41|52|builtin-default"), run(completed, queried, "after").outputs());
            assertEquals(1, fixture.executionService().executions(fixture.session()).size());
        }
    }

    /** 在三处分别验证非法配置、默认值与非内置类型被拒绝，随后删除草稿。
     * @param position 输入所在的定义位置
     * @param violation 要验证的非法规则
     */
    @ParameterizedTest
    @CsvSource({"flow,configuration", "flow,default", "flow,custom",
        "task,configuration", "task,default", "task,custom",
        "resume,configuration", "resume,default", "resume,custom"})
    void s2UserCannotPublishInvalidBuiltInDefinitions(String position, String violation) {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            String marker = switch (position) {
                case "flow" -> "min: 1";
                case "task" -> "min: 2";
                case "resume" -> "min: 3";
                default -> throw new IllegalArgumentException("Unknown input position");
            };
            String source = fullFlowYaml("uc12-s2-" + position + "-" + violation);
            source = switch (violation) {
                case "configuration" -> source.replace(marker, "min: 101");
                case "default" -> source.replace("defaultValue: " + switch (position) {
                    case "flow" -> "10";
                    case "task" -> "20";
                    default -> "30";
                } + ".0", "defaultValue: 101");
                default -> source.replaceAll("type: INTEGER(\\s+required: true)?(\\s+" + marker + ")",
                    "type: BUSINESS_CODE$1$2");
            };
            assertTrue(!violation.equals("custom") || source.contains("BUSINESS_CODE"));
            Flow draft = fixture.flowService().save(fixture.session(), PublishFlowCommand.from(source));
            Flow saved = fixture.flowService().draft(fixture.session(), draft.key()).orElseThrow();
            assertEquals(source, saved.source());
            if (!position.equals("flow")) {
                assertEquals(2, saved.inputs().size());
                assertBuiltInDefinition(saved.inputs().getFirst(), "order", 1, 10, true);
            }

            RuntimeException rejected = assertThrows(RuntimeException.class, () -> fixture.flowService()
                .save(fixture.session(), PublishFlowCommand.from(draft.key(), false)));
            assertTrue(causes(rejected).contains(switch (violation) {
                case "configuration" -> "min must not exceed max";
                case "default" -> "at most 100";
                default -> "BUSINESS_CODE";
            }), causes(rejected));
            Flow unchanged = fixture.flowService().draft(fixture.session(), draft.key()).orElseThrow();
            assertEquals(source, unchanged.source());
            assertEquals(saved.version(), unchanged.version());
            assertTrue(fixture.flowService().latestFlow(fixture.session(), draft.key()).isEmpty());
            assertTrue(fixture.flowService().flow(fixture.session(), draft.key(), saved.version()).isEmpty());
            assertTrue(fixture.executionService().executions(fixture.session()).isEmpty());

            fixture.flowService().delete(fixture.session(), draft.key(), true);
            assertTrue(fixture.flowService().draft(fixture.session(), draft.key()).isEmpty());
            assertTrue(fixture.flowService().latestFlow(fixture.session(), draft.key()).isEmpty());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    /** 拒绝越界启动值且不创建运行实例，合法数值转换为整数后完成流程。 */
    @Test
    void s3UserCorrectsAnInvalidBuiltInStartupValue() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(startFlowYaml("uc12-s3"));
            WorkflowException rejected = assertThrows(WorkflowException.class, () -> fixture.executionService()
                .create(fixture.session(), flow.key(), Optional.of(flow.version()), Map.of("order", 101L)));
            assertTrue(causes(rejected).contains("at most 100"));
            assertTrue(fixture.executionService().executions(fixture.session()).isEmpty());
            assertTrue(fixture.pausedTaskRuns().isEmpty());

            String executionId = start(fixture, flow, Map.of("order", 63L));
            Execution completed = assertCompleted(fixture, executionId);
            assertEquals(Map.of("order", 63), completed.inputs());
            assertEquals(Map.of("value", "63"), run(completed, flow, "after").outputs());
            assertEquals(1, completed.taskRuns().size());
            assertEquals(1, fixture.executionService().executions(fixture.session()).size());
        }
    }

    /** 拒绝越界恢复值且保持等待，纠正后同一实例继续并完成。 */
    @Test
    void s4UserCorrectsAnInvalidBuiltInResumeValue() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(resumeFlowYaml("uc12-s4"));
            String executionId = start(fixture, flow, Map.of());
            PausedTaskRunRef pause = fixture.waitingForExecution(executionId);
            assertRejectedResume(fixture, flow, pause);

            fixture.resume(pause, Map.of("decision", 74L));
            Execution completed = assertCompleted(fixture, executionId);
            assertEquals(Map.of("decision", 74), completed.requireTaskRun(pause.taskRunId()).outputs());
            assertEquals(Map.of("value", "74"), run(completed, flow, "after").outputs());
            assertEquals(1, fixture.executionService().executions(fixture.session()).size());
        }
    }

    /** 重启真实容器后重新查询定义和等待任务，验证输入规则并完成同一实例。 */
    @Test
    void s5UserReentersTheSystemAndContinuesWithPersistedBuiltInRules() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(fullFlowYaml("uc12-s5"));
            String flowKey = flow.key();
            long flowVersion = flow.version();
            String executionId = start(fixture, flow, Map.of("order", 81L));
            String pauseId = fixture.waitingForExecution(executionId).taskRunId();
            fixture.restartServer();

            Flow restored = fixture.flowService().flow(fixture.session(), flowKey, flowVersion).orElseThrow();
            assertBuiltInDefinitions(restored);
            Execution waiting = query(fixture, executionId);
            assertEquals(Map.of("order", 81, "reference", "builtin-default"), waiting.inputs());
            PausedTaskRunRef pause = fixture.waitingForExecution(executionId);
            assertEquals(pauseId, pause.taskRunId());
            assertRejectedResume(fixture, restored, pause);

            fixture.resume(pause, Map.of("decision", 92L));
            Execution completed = assertCompleted(fixture, executionId);
            assertEquals(Map.of("order", 81, "reference", "builtin-default"), completed.inputs());
            assertEquals(Map.of("decision", 92), completed.requireTaskRun(pauseId).outputs());
            assertEquals(Map.of("value", "81|92|builtin-default"), run(completed, restored, "after").outputs());
            assertEquals(1, fixture.executionService().executions(fixture.session()).size());
        }
    }

    /**
     * 查询最终实例并核对终态以及没有等待或活动任务。
     * @param fixture 真实公开服务夹具
     * @param executionId 公开返回的运行编号
     * @return 查询到的已完成实例
     */
    private static Execution assertCompleted(WorkflowUcFixture fixture, String executionId) {
        fixture.awaitStable(executionId);
        Execution completed = query(fixture, executionId);
        assertEquals(executionId, completed.id());
        assertEquals(State.Type.SUCCESS, completed.state().current());
        assertTrue(completed.unfinishedTaskRuns().isEmpty());
        assertTrue(completed.activeTaskRuns().isEmpty());
        assertTrue(fixture.pausedTaskRuns().isEmpty());
        return completed;
    }

    /**
     * 提交非法恢复值并核对公开可见的等待快照没有变化。
     * @param fixture 真实服务夹具
     * @param flow 用于定位后续步骤的正式定义
     * @param pause 公开查询获得的当前 Pause
     */
    private static void assertRejectedResume(WorkflowUcFixture fixture, Flow flow, PausedTaskRunRef pause) {
        Execution before = query(fixture, pause.executionId());
        WorkflowException rejected = assertThrows(WorkflowException.class, () -> fixture.executionService()
            .resume(fixture.session(), pause.executionId(), pause.taskRunId(), Map.of("decision", 101L)));
        assertTrue(causes(rejected).contains("at most 100"));
        Execution unchanged = query(fixture, pause.executionId());
        assertEquals(before.state(), unchanged.state());
        assertEquals(State.Type.PAUSED, unchanged.state().current());
        assertEquals(before.taskRuns().stream().map(TaskRun::id).toList(),
            unchanged.taskRuns().stream().map(TaskRun::id).toList());
        assertEquals(before.requireTaskRun(pause.taskRunId()).state(), unchanged.requireTaskRun(pause.taskRunId()).state());
        assertEquals(Map.of(), unchanged.requireTaskRun(pause.taskRunId()).outputs());
        assertTrue(unchanged.taskRunsForTask(task(flow, "after").id()).isEmpty());
    }

    /**
     * 核对三处内置输入的类型、配置和规范化默认值。
     * @param flow 通过公开入口重新查询的定义
     */
    private static void assertBuiltInDefinitions(Flow flow) {
        assertBuiltInDefinition(flow.inputs().getFirst(), "order", 1, 10, true);
        assertBuiltInDefinition(task(flow, "before").inputs().getFirst(), "local", 2, 20, false);
        Pause pause = assertInstanceOf(Pause.class, task(flow, "wait"));
        assertBuiltInDefinition(pause.onResume().getFirst(), "decision", 3, 30, true);
        Input<?> builtIn = flow.inputs().get(1);
        assertInstanceOf(StringInput.class, builtIn);
        assertEquals("reference", builtIn.getKey());
        assertEquals("builtin-default", builtIn.getDefaultValue());
    }

    /**
     * 核对单个内置输入的公开定义。
     * @param input 公开查询的输入定义
     * @param key 预期字段标识
     * @param min 预期包含下界
     * @param defaultValue 预期规范化默认值
     * @param required 预期必填标记
     */
    private static void assertBuiltInDefinition(Input<?> input, String key, int min, int defaultValue,
        boolean required) {
        IntegerInput integer = assertInstanceOf(IntegerInput.class, input);
        assertEquals("INTEGER", input.getType());
        assertEquals(key, input.getKey());
        assertEquals(key, input.getDisplayName());
        assertEquals(required, input.isRequired());
        assertEquals(min, integer.getMin());
        assertEquals(100, integer.getMax());
        assertEquals(defaultValue, input.getDefaultValue());
    }

    /**
     * 通过公开服务启动并等待稳定状态。
     * @param fixture 真实服务夹具
     * @param flow 用户选择的精确正式版本
     * @param values 提交值，只读
     * @return 公开返回的稳定运行编号
     */
    private static String start(WorkflowUcFixture fixture, Flow flow, Map<String, ?> values) {
        String executionId = fixture.executionService().create(fixture.session(), flow.key(),
            Optional.of(flow.version()), values).getExecutionId();
        fixture.awaitStable(executionId);
        return executionId;
    }

    /**
     * 按公开编号查询运行实例。
     * @param fixture 真实服务夹具
     * @param executionId 用户可访问的运行编号
     * @return 新查询的实例
     */
    private static Execution query(WorkflowUcFixture fixture, String executionId) {
        return fixture.executionService().execution(fixture.session(), executionId).orElseThrow();
    }

    /**
     * 在公开定义中按业务标识定位步骤。
     * @param flow 查询到的定义
     * @param key 定义中的步骤业务标识
     * @return 匹配的步骤定义
     */
    private static Task task(Flow flow, String key) {
        return flow.allTasks().stream().filter(candidate -> candidate.key().equals(key)).findFirst().orElseThrow();
    }

    /**
     * 核对观察步骤恰好成功执行一次并读取公开结果。
     * @param execution 查询到的运行实例
     * @param flow 对应正式定义
     * @param key 观察步骤业务标识
     * @return 唯一次成功步骤运行
     */
    private static TaskRun run(Execution execution, Flow flow, String key) {
        var runs = execution.taskRunsForTask(task(flow, key).id());
        assertEquals(1, runs.size());
        assertEquals(State.Type.SUCCESS, runs.getFirst().state().current());
        return runs.getFirst();
    }

    /**
     * 提取异常链中的稳定校验消息以验证拒绝，不匹配堆栈。
     * @param failure 公开操作产生的异常
     * @return 合并后的原因消息
     */
    private static String causes(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            result.append(cause.getMessage()).append('\n');
        }
        return result.toString();
    }

    /**
     * 生成三处内置整数输入以及字符串对照字段的独立流程。
     * @param key 场景独立的流程业务标识
     * @return 可发布的 YAML 原文
     */
    private static String fullFlowYaml(String key) {
        return """
            key: %s
            inputs:
              - key: order
                type: %s
                required: true
                min: 1
                max: 100
                defaultValue: 10.0
              - key: reference
                type: STRING
                defaultValue: builtin-default
            tasks:
              - key: before
                type: %s
                expression: '{{ inputs.order }}|{{ inputs.reference }}'
                inputs:
                  - key: local
                    type: %s
                    min: 2
                    max: 100
                    defaultValue: 20.0
            %s
              - key: after
                type: %s
                expression: '{{ inputs.order }}|{{ outputs.wait.decision }}|{{ inputs.reference }}'
            """.formatted(key, "INTEGER", Snapshot.class.getCanonicalName(),
                "INTEGER", pauseYaml().indent(2), Snapshot.class.getCanonicalName());
    }

    /**
     * 生成启动拒绝及纠正的独立最小流程。
     * @param key 场景流程业务标识
     * @return 可发布的 YAML 原文
     */
    private static String startFlowYaml(String key) {
        return """
            key: %s
            inputs:
              - key: order
                type: %s
                min: 1
                max: 100
                required: true
            tasks:
              - key: after
                type: %s
                expression: '{{ inputs.order }}'
            """.formatted(key, "INTEGER", Snapshot.class.getCanonicalName());
    }

    /**
     * 生成后续步骤读取恢复结果的独立最小 Pause 流程。
     * @param key 场景流程业务标识
     * @return 可发布的 YAML 原文
     */
    private static String resumeFlowYaml(String key) {
        return """
            key: %s
            tasks:
            %s
              - key: after
                type: %s
                expression: '{{ outputs.wait.decision }}'
            """.formatted(key, pauseYaml().indent(2), Snapshot.class.getCanonicalName());
    }

    /** @return 声明独立内置恢复输入的 Pause YAML */
    private static String pauseYaml() {
        return """
            - key: wait
              type: org.cses.flow.extensions.flow.Pause
              onPause:
                key: prepare
                type: org.cses.flow.extensions.log.Log
                message: ready
              onResume:
                - key: decision
                  type: %s
                  required: true
                  min: 3
                  max: 100
                  defaultValue: 30.0
            """.formatted("INTEGER");
    }

    /** 确定性的 Worker 观察步骤，自身不执行 Input 转换或校验。 */
    @Plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class Snapshot extends Task implements RunnableTask<SnapshotOutput> {
        private String expression;

        /**
         * 根据真实运行值渲染配置表达式。
         * @param context 真实 Worker 上下文，只读
         * @return 声明输出类型中的观察字符串
         */
        @Override
        public SnapshotOutput run(RunContext context) {
            return SnapshotOutput.from(context.render(TemplateExpression.parse(expression)));
        }

        /** @return 用于任务定义等值比较的表达式 */
        @Override
        protected Object typeSpecificEqualityState() {
            return expression;
        }
    }

    /** 保存此测试任务的具体业务结果。 */
    public record SnapshotOutput(String value) implements org.cses.flow.core.domains.tasks.Output {
        /**
         * 创建本次运行的业务输出。
         * @param value 本次运行的只读结果值
         * @return 包含该值的新输出
         */
        public static SnapshotOutput from(String value) {
            return new SnapshotOutput(value);
        }
    }
}
