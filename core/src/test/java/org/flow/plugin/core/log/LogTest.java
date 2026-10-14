package org.flow.plugin.core.log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.executions.LogEntry;
import org.flow.core.queues.DispatchQueueInterface;
import org.flow.core.runners.RunContext;
import org.flow.core.runners.RunContextFactory;
import org.flow.core.utils.IdUtils;
import org.flow.core.utils.TestsUtils;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class LogTest {
    @Inject
    RunContextFactory runContextFactory;

    @Inject
    private DispatchQueueInterface<LogEntry> logQueue;

    @Test
    void shouldLogNonStringMessageListElementsInsteadOfCrashing() throws Exception {
        List<LogEntry> logs = new ArrayList<>();
        logQueue.addListener(logs::add);

        Log task = Log.builder()
            .id(IdUtils.create())
            .type(Log.class.getName())
            .message(List.of("hello", 42, true))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        task.run(runContext);

        List<LogEntry> matchingLogs = TestsUtils.awaitLogs(logs, 3);

        assertThat(matchingLogs.stream().filter(logEntry -> logEntry.getMessage().equals("hello")).count()).isEqualTo(1L);
        assertThat(matchingLogs.stream().filter(logEntry -> logEntry.getMessage().equals("42")).count()).isEqualTo(1L);
        assertThat(matchingLogs.stream().filter(logEntry -> logEntry.getMessage().equals("true")).count()).isEqualTo(1L);
    }
}
