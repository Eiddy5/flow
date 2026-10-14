package org.flow.worker;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.flow.core.metrics.MetricRegistry;
import org.flow.core.server.ServerConfig;
import org.flow.core.services.MaintenanceService;
import org.flow.worker.fetchers.WorkerJobFetcher;

import io.micronaut.context.event.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkerAgentTest {

    @Test
    void shouldUseConfiguredJobBufferSizeWhenSet() {
        assertThat(workerAgent(2).jobBufferSize(8)).isEqualTo(2);
    }

    @Test
    void shouldCapJobBufferSizeToThreadCountWhenConfiguredAbove() {
        assertThat(workerAgent(16).jobBufferSize(8)).isEqualTo(8);
    }

    @Test
    void shouldDefaultJobBufferSizeToThreadCountWhenNotSet() {
        assertThat(workerAgent(null).jobBufferSize(8)).isEqualTo(8);
    }

    @SuppressWarnings("unchecked")
    private static WorkerAgent workerAgent(Integer jobBufferSize) {
        return new WorkerAgent(
            mock(ApplicationEventPublisher.class),
            mock(WorkerJobExecutor.class),
            mock(WorkerJobFetcher.class),
            List.of(),
            mock(MaintenanceService.class),
            mock(MetricRegistry.class),
            mock(ServerConfig.class),
            new WorkerConfig(null, jobBufferSize)
        );
    }
}
