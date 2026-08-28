package org.cses.flow.extensions.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.cses.flow.core.domains.expressions.TemplateExpression;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.plugins.TaskPluginTestSupport;
import org.cses.flow.core.runner.RunContext;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LogTest {

    @Test
    void rendersMessageAndCompletesWithoutOutputs() {
        LogCapture logs = LogCapture.start();
        Log log = Log.builder()
            .id("log-id")
            .key("write-log")
            .message(TemplateExpression.parse(
                "结果：{{ outputs.prepare.result }}"
            ))
            .build();

        RunResult result;
        try (logs) {
            result = log.run(context(Map.of(
                "outputs", Map.of(
                    "prepare", Map.of("result", "ready")
                )
            )));
        }

        assertEquals(State.Type.SUCCESS, result.targetState());
        assertEquals(Map.of(), result.outputs());
        assertEquals(
            "结果：{{ outputs.prepare.result }}",
            log.message()
        );
        assertEquals(List.of("结果：ready"), logs.messages());
    }

    @Test
    void returnsExplicitFailureWhenRuntimePathIsMissing() {
        LogCapture logs = LogCapture.start();
        Log log = Log.builder()
            .id("log-id")
            .key("write-log")
            .message(TemplateExpression.parse("{{ outputs.missing }}"))
            .build();

        RunResult result;
        try (logs) {
            result = log.run(context(Map.of()));
        }

        assertEquals(State.Type.FAILED, result.targetState());
        assertEquals(
            "Log message could not be rendered: "
                + "Task template expression path is missing: "
                + "outputs.missing",
            result.error()
        );
        assertEquals(List.of(), logs.messages());
    }

    @Test
    void requiresAMessageWhenCreatedDirectly() {
        Log log = Log.builder()
            .id("log-id")
            .key("write-log")
            .build();

        assertThrows(
            jakarta.validation.ConstraintViolationException.class,
            () -> TaskPluginTestSupport.builtInContext()
                .modelValidator()
                .validate(log)
        );
    }

    @Test
    void materializesAStringMessageAndRejectsInvalidSyntax() {
        TaskPluginTestSupport.Context plugins =
            TaskPluginTestSupport.builtInContext();
        Flow flow = plugins.deploy(
            "company-1",
            "flow-1",
            Map.of(
                "key", "log-flow",
                "tasks", List.of(Map.of(
                    "key", "write-log",
                    "type", Log.class.getCanonicalName(),
                    "message", "结果：{{ outputs.result }}"
                ))
            ),
            null,
            ActorRef.create("user-1", "User 1"),
            1_785_312_000_000L
        );

        Log materialized = assertInstanceOf(
            Log.class,
            flow.tasks().getFirst()
        );
        assertEquals("结果：{{ outputs.result }}", materialized.message());

        assertThrows(
            IllegalArgumentException.class,
            () -> plugins.deploy(
                "company-1",
                "flow-2",
                Map.of(
                    "key", "invalid-log-flow",
                    "tasks", List.of(Map.of(
                        "key", "write-log",
                        "type", Log.class.getCanonicalName(),
                        "message", "结果：{{ outputs.result"
                    ))
                ),
                null,
                ActorRef.create("user-1", "User 1"),
                1_785_312_000_001L
            )
        );
    }

    private static RunContext context(Map<String, ?> variables) {
        return RunContext.builder().variables(variables).build();
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
