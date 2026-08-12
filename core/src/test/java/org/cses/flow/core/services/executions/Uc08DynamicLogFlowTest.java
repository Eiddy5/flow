package org.cses.flow.core.services.executions;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.RunResult;
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
                    outputs:
                      - key: result
                        type: STRING
                  - key: write-log
                    type: org.cses.flow.extensions.log.Log
                    dependOn:
                      - prepare
                    message: "处理结果：{{ dependOnOutputs.prepare.result }}"
                  - key: observe
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                    dependOn:
                      - write-log
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

    @Test
    void s2RejectsInvalidMessageExpressionBeforePublishing() {
        LogCapture logs = LogCapture.start();
        try (logs;
             WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            FlowDraft draft = fixture.flowService().saveDraft(
                fixture.session(),
                """
                key: uc08-s2-flow
                description: invalid log expression
                tasks:
                  - key: write-log
                    type: org.cses.flow.extensions.log.Log
                    message: "结果：{{ outputs.result"
                """
            );
            long executionsBefore = fixture.executionCount();

            assertThrows(
                IllegalArgumentException.class,
                () -> fixture.flowService().deploy(
                    fixture.session(),
                    draft.id()
                )
            );

            assertTrue(fixture.flowService().latestFlow(
                fixture.session(),
                draft.id()
            ).isEmpty());
            assertEquals(executionsBefore, fixture.executionCount());
            assertTrue(logs.messages().isEmpty());
            fixture.flowService().deleteDraft(
                fixture.session(),
                draft.id()
            );
            assertTrue(fixture.flowService().draft(
                fixture.session(),
                draft.id()
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
                    outputs:
                      - key: available
                        type: STRING
                  - key: write-log
                    type: org.cses.flow.extensions.log.Log
                    dependOn:
                      - prepare
                    message: "结果：{{ dependOnOutputs.prepare.missing }}"
                  - key: never-run
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                    dependOn:
                      - write-log
                """);

            Execution failed = fixture.startAndAwait(flow);
            Task target = task(flow, "write-log");
            Task neverRun = task(flow, "never-run");
            TaskRun targetRun = run(failed, target);

            assertEquals(State.Type.FAILED, failed.state().current());
            assertEquals(State.Type.FAILED, targetRun.state().current());
            assertTrue(targetRun.error().orElseThrow().contains(
                "dependOnOutputs.prepare.missing"
            ));
            assertTrue(failed.taskRunsForTask(neverRun.id()).isEmpty());
            assertTrue(logs.messages().isEmpty());
            assertFalse(logs.messages().stream().anyMatch(message ->
                message.contains("{{") || message.contains("null")
            ));
            assertTrue(failed.activeTaskRuns().isEmpty());
        }
    }

    /** Supplies deterministic predecessor output through the normal worker. */
    @Plugin
    @SuperBuilder
    @NoArgsConstructor
    public static final class LogInputTask
        extends Task implements RunnableTask {

        @Override
        public RunResult run(RunContext context) {
            return switch (key()) {
                case "prepare" -> RunResult.success(Map.of(
                    outputs().getFirst().getKey(),
                    "ready"
                ));
                default -> RunResult.success(Map.of());
            };
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

    private static final class LogCapture implements AutoCloseable {

        private final ch.qos.logback.classic.Logger logger;
        private final ListAppender<ILoggingEvent> appender;

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
}
