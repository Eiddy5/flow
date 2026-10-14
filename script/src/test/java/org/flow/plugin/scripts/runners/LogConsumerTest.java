package org.flow.plugin.scripts.runners;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

import org.flow.core.context.TestRunContextFactory;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.executions.LogEntry;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.Task;
import org.flow.core.models.tasks.runners.TaskCommands;
import org.flow.core.queues.DispatchQueueInterface;
import org.flow.core.runners.RunContext;
import org.flow.core.utils.Await;
import org.flow.core.utils.TestsUtils;
import org.flow.plugin.scripts.exec.scripts.runners.CommandsWrapper;
import org.flow.plugin.core.runner.Process;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class LogConsumerTest {
    private static final Task TASK = new Task() {
        @Override
        public String getId() {
            return "id";
        }

        @Override
        public String getType() {
            return "type";
        }
    };

    @Inject
    private TestRunContextFactory runContextFactory;

    @Inject
    private DispatchQueueInterface<LogEntry> logQueue;

    @Test
    void run() throws Exception {
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, TASK, ImmutableMap.of());
        String outputValue = "a".repeat(10000);
        TaskCommands taskCommands = new CommandsWrapper(runContext)
            .withCommands(
                Property.ofValue(
                    List.of(
                        "/bin/sh", "-c",
                        "echo \"::{\\\"outputs\\\":{\\\"someOutput\\\":\\\"" + outputValue + "\\\"}}::\"\n" +
                            "echo -n another line"
                    )
                )
            );
        var run = new Process().run(
            runContext,
            taskCommands,
            Collections.emptyList()
        );
        Await.until(() -> run.getLogConsumer().getStdOutCount() == 2, null, Duration.ofSeconds(5));
        assertThat(run.getLogConsumer().getStdOutCount()).isEqualTo(2);
        assertThat(run.getLogConsumer().getOutputs().get("someOutput")).isEqualTo(outputValue);
    }

    @Test
    void testWithMultipleCrInSameFrame() throws Exception {

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, TASK, ImmutableMap.of());
        StringBuilder outputValue = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            outputValue.append(Integer.toString(i).repeat(100)).append("\r")
                .append(Integer.toString(i).repeat(800)).append("\r")
                .append(Integer.toString(i).repeat(2000)).append("\r");
        }
        TaskCommands taskCommands = new CommandsWrapper(runContext).withCommands(
            Property.ofValue(
                List.of(
                    "/bin/sh", "-c",
                    "echo " + outputValue +
                        "echo -n another line"
                )
            )
        );
        var run = new Process().run(
            runContext,
            taskCommands,
            Collections.emptyList()
        );

        Await.until(() -> run.getLogConsumer().getStdOutCount() == 10, null, Duration.ofSeconds(5));
        assertThat(run.getLogConsumer().getStdOutCount()).isEqualTo(10);
    }

    @Test
    void logs() throws Exception {
        List<LogEntry> logs = new CopyOnWriteArrayList<>();
        logQueue.addListener(logs::add);

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, TASK, ImmutableMap.of());
        TaskCommands taskCommands = new CommandsWrapper(runContext).withCommands(
            Property.ofValue(
                List.of(
                    "/bin/sh", "-c",
                    """
                            echo '::{"logs": [{"level":"INFO","message":"Hello World"}]}::'
                            echo '::{"logs": [{"level":"ERROR","message":"Hello Error"}]}::'
                            echo '::{"logs": [{"level":"TRACE","message":"Hello Trace"}, {"level":"TRACE","message":"Hello Trace 2"}]}::'
                        """
                )
            )
        );

        new Process().run(
            runContext,
            taskCommands,
            Collections.emptyList()
        );

        // Assert the four emitted user events; runner infrastructure logs vary by implementation.
        Await.until(() -> logs.stream().filter(m -> m.getMessage().startsWith("Hello ")).count() == 4,
            null, Duration.ofSeconds(20));
        assertThat(logs.stream().filter(m -> m.getMessage().startsWith("Hello "))
            .map(m -> m.getLevel() + ":" + m.getMessage()).toList())
            .containsExactlyInAnyOrder("INFO:Hello World", "ERROR:Hello Error",
                "TRACE:Hello Trace", "TRACE:Hello Trace 2");
    }
}
