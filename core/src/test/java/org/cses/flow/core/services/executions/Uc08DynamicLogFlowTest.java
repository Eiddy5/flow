package org.cses.flow.core.services.executions;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.extensions.log.Log;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC: docs/uc/flow/UC-08 用户在流程中输出动态日志.md
 */
class Uc08DynamicLogFlowTest {

    /** 只由目标 Log 输出动态消息，后置观察步骤通过真实 Worker 完成。 */
    @Test
    void s1RendersPrecedingResultOnceAndContinuesTheFlow() {
        LogCapture logs = LogCapture.start();
        try (logs;
             WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy("""
                key: uc08-s1-flow
                description: render one dynamic log
                tasks:
                  - key: prepare
                    type: org.cses.flow.core.services.executions.Uc08DynamicLogFlowTest.LogInputTask
                  - key: write-log
                    type: org.cses.flow.extensions.log.Log
                    message: "处理结果：{{ outputs.prepare.result }}"
                  - key: observe
                    type: org.cses.flow.core.services.executions.Uc08DynamicLogFlowTest.LogInputTask
                """);

            Execution completed = fixture.startAndAwait(flow);
            Task target = task(flow, "write-log");
            Task observe = task(flow, "observe");

            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(
                State.Type.SUCCESS,
                run(completed, target).state().current()
            );
            assertEquals(1, completed.taskRunsForTask(target.id()).size());
            assertEquals(1, completed.taskRunsForTask(observe.id()).size());
            assertEquals(
                State.Type.SUCCESS,
                run(completed, observe).state().current()
            );
            assertEquals(
                List.of("处理结果：ready"),
                logs.messages()
            );
            assertTrue(completed.activeTaskRuns().isEmpty());
        }
    }

    /** 验证发布和启动均拒绝无效消息且清理草稿。 */
    @Test
    void s2RejectsInvalidMessageExpressionBeforePublishing() {
        LogCapture logs = LogCapture.start();
        try (logs;
             WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow draft = fixture.flowService().save(
                fixture.session(),
                PublishFlowCommand.from("""
                key: uc08-s2-flow
                description: invalid log expression
                tasks:
                  - key: write-log
                    type: org.cses.flow.extensions.log.Log
                    message: "结果：{{ outputs.result"
                """)
            );
            int executionsBefore = fixture.executionService().executions(fixture.session()).size();

            assertThrows(
                IllegalArgumentException.class,
                () -> fixture.flowService().save(
                    fixture.session(),
                    PublishFlowCommand.from(draft.key(), false)
                )
            );

            assertTrue(fixture.flowService().latestFlow(
                fixture.session(),
                draft.key()
            ).isEmpty());
            assertThrows(org.cses.flow.core.exceptions.WorkflowException.class,
                () -> fixture.executionService().create(fixture.session(), draft.key(), java.util.Optional.empty(), Map.of()));
            assertEquals(executionsBefore, fixture.executionService().executions(fixture.session()).size());
            assertTrue(logs.messages().isEmpty());
            fixture.flowService().delete(
                fixture.session(),
                draft.key(),
                true
            );
            assertTrue(fixture.flowService().draft(
                fixture.session(),
                draft.key()
            ).isEmpty());
        }
    }

    @Test
    void s3MissingRuntimePathFailsWithoutWritingAMisleadingLog() {
        LogCapture logs = LogCapture.start();
        try (logs;
             WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy("""
                key: uc08-s3-flow
                description: missing log expression value
                tasks:
                  - key: prepare
                    type: org.cses.flow.core.services.executions.Uc08DynamicLogFlowTest.LogInputTask
                  - key: write-log
                    type: org.cses.flow.extensions.log.Log
                    message: "结果：{{ outputs.prepare.missing }}"
                  - key: never-run
                    type: org.cses.flow.extensions.log.Log
                    message: "test step"
                """);

            Execution failed = fixture.startAndAwait(flow);
            Task target = task(flow, "write-log");
            Task neverRun = task(flow, "never-run");
            TaskRun targetRun = run(failed, target);

            assertEquals(State.Type.FAILED, failed.state().current());
            assertEquals(State.Type.FAILED, targetRun.state().current());
            assertTrue(targetRun.error().orElseThrow().contains(
                "outputs.prepare.missing"
            ));
            assertTrue(failed.taskRunsForTask(neverRun.id()).isEmpty());
            assertTrue(logs.messages().isEmpty());
            assertFalse(logs.messages().stream().anyMatch(message ->
                message.contains("{{") || message.contains("null")
            ));
            assertTrue(failed.activeTaskRuns().isEmpty());
        }
    }

    /** 通过真实 Worker 提供固定的前置结果。 */
    @Plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class LogInputTask
        extends Task implements RunnableTask<LogInputOutput> {

        /**
         * 返回可被后续步骤引用的具体结果字段。
         * @param context 本次调用上下文；此确定性样本不读取它
         * @return 包含 result 字段的明确输出
         */
        @Override
        public LogInputOutput run(RunContext context) {
            return LogInputOutput.from("ready");
        }

    }

    private static Task task(Flow flow, String key) {
        return flow.allTasks().stream()
            .filter(task -> task.key().equals(key))
            .findFirst()
            .orElseThrow();
    }

    private static TaskRun run(Execution execution, Task task) {
        return execution.taskRunsForTask(task.id()).stream()
            .findFirst()
            .orElseThrow();
    }

    private static class LogCapture implements AutoCloseable {

        private ch.qos.logback.classic.Logger logger;
        private ListAppender<ILoggingEvent> appender;

        private LogCapture(
            ch.qos.logback.classic.Logger logger,
            ListAppender<ILoggingEvent> appender
        ) {
            this.logger = logger;
            this.appender = appender;
        }

        private static LogCapture start() {
            ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                    Log.class
                );
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            return new LogCapture(logger, appender);
        }

        private List<String> messages() {
            return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** 日志前置任务的明确结果字段。 */
    public record LogInputOutput(String result) implements org.cses.flow.core.domains.tasks.Output {
        /**
         * 创建确定的前置结果。
         * @param result 本场景字符串结果
         * @return 含该字段的新输出
         */
        public static LogInputOutput from(String result) {
            return new LogInputOutput(result);
        }
    }
}
