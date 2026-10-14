package org.flow.plugin.core.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.ExecuteFlow;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.LogEntry;
import org.flow.core.models.flows.State;
import org.flow.core.queues.DispatchQueueInterface;
import org.flow.core.runners.RunContext;
import org.flow.core.runners.RunContextFactory;
import org.flow.core.utils.IdUtils;
import org.flow.core.utils.TestsUtils;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

@FlowTest(startRunner = true)
public class AssertTest {
    @Inject
    RunContextFactory runContextFactory;

    @Inject
    private DispatchQueueInterface<LogEntry> logQueue;

    @Test
    void success() throws Exception {
        Assert task = Assert.builder()
            .id(IdUtils.create())
            .type(Assert.class.getName())
            .conditions(
                List.of(
                    "{{ inputs.key == 'value' }}",
                    "{{ 42 == 42 }}"
                )
            )
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of("key", "value"));
        task.run(runContext);

        assertThat(runContext.metrics().stream().filter(e -> e.getName().equals("success")).findFirst().orElseThrow().getValue()).isEqualTo(2d);
        assertThat(runContext.metrics().stream().filter(e -> e.getName().equals("failed")).findFirst().orElseThrow().getValue()).isEqualTo(0d);
    }

    @Test
    void failed() {
        List<LogEntry> logs = new ArrayList<>();
        logQueue.addListener(logs::add);

        Assert task = Assert.builder()
            .id(IdUtils.create())
            .type(Assert.class.getName())
            .conditions(
                List.of(
                    "{{ 42 == 42 }}",
                    "{{ inputs.key == 'value1' }}",
                    "{{ 42 == 42 }}",
                    "{{ inputs.key == 'value2' }}",
                    "{{ 42 == 42 }}"
                )
            )
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of("key", "value"));

        Exception exception = assertThrows(Exception.class, () -> task.run(runContext));

        assertThat(exception.getMessage()).contains("2 assertions failed");

        List<LogEntry> matchingLog = TestsUtils.awaitLogs(logs, 2);

        assertThat(matchingLog.stream().filter(logEntry -> logEntry.getMessage().contains("inputs.key == 'value1'")).count()).isEqualTo(1L);
        assertThat(matchingLog.stream().filter(logEntry -> logEntry.getMessage().contains("inputs.key == 'value2'")).count()).isEqualTo(1L);

        assertThat(runContext.metrics().stream().filter(e -> e.getName().equals("success")).findFirst().orElseThrow().getValue()).isEqualTo(3d);
        assertThat(runContext.metrics().stream().filter(e -> e.getName().equals("failed")).findFirst().orElseThrow().getValue()).isEqualTo(2d);
    }

    @Test
    @ExecuteFlow("flows/valids/assert.yaml")
    void dontFailOnCondition(Execution execution) {
        assertThat(execution.getTaskRunList()).hasSize(2);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.FAILED);
    }
}
