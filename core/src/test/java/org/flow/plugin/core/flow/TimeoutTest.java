package org.flow.plugin.core.flow;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;

import org.junitpioneer.jupiter.RetryingTest;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.LogEntry;
import org.flow.core.models.flows.Flow;
import org.flow.core.models.flows.GenericFlow;
import org.flow.core.models.flows.State;
import org.flow.core.models.property.Property;
import org.flow.core.queues.DispatchQueueInterface;
import org.flow.core.queues.QueueException;
import org.flow.core.repositories.FlowRepositoryInterface;
import org.flow.core.runners.TestRunnerUtils;
import org.flow.core.utils.IdUtils;
import org.flow.core.utils.TestsUtils;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest(startRunner = true)
class TimeoutTest {
    @Inject
    FlowRepositoryInterface flowRepository;

    @Inject
    private DispatchQueueInterface<LogEntry> workerTaskLogQueue;

    @Inject
    private TestRunnerUtils runnerUtils;

    @RetryingTest(5) // Flaky on CI but never locally even with 100 repetitions
    void timeout() throws TimeoutException, QueueException {
        List<LogEntry> logs = new CopyOnWriteArrayList<>();
        workerTaskLogQueue.addListener(logs::add);

        Flow flow = Flow.builder()
            .id(IdUtils.create())
            .namespace("org.flow.unittest")
            .revision(1)
            .tasks(
                Collections.singletonList(
                    Sleep.builder()
                        .id("test")
                        .type(Sleep.class.getName())
                        .duration(Property.ofValue(Duration.ofSeconds(100)))
                        .timeout(Property.ofValue(Duration.ofMillis(1)))
                        .build()
                )
            )
            .build();

        flowRepository.create(GenericFlow.of(flow));

        Execution execution = runnerUtils.runOne(flow.getTenantId(), flow.getNamespace(), flow.getId());

        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.FAILED);
        List<LogEntry> matchingLogs = TestsUtils.awaitLogs(logs, logEntry -> logEntry.getMessage().contains("Timeout"), 2);
        assertThat(matchingLogs.size()).isEqualTo(2);
    }
}
