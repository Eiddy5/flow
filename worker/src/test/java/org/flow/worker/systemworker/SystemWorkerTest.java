package org.flow.worker.systemworker;

import org.flow.worker.fetchers.WorkerJobFetcher;
import org.flow.worker.senders.QueueWorkerIOSender;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.flow.core.metrics.MetricRegistry;
import org.flow.core.server.ServerConfig;
import org.flow.core.server.ServiceStateChangeEvent;
import org.flow.core.services.MaintenanceService;
import org.flow.core.worker.WorkerQueues;
import org.flow.worker.WorkerJobExecutor;

import io.micronaut.context.event.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SystemWorkerTest {

    @Test
    void shouldResolveReservedSystemWorkerQueue() {
        TestableSystemWorker worker = newSystemWorker();

        assertThat(worker.resolveWorkerGroupIdForTest()).isEqualTo(WorkerQueues.SYSTEM_ID);
    }

    @SuppressWarnings("unchecked")
    private static TestableSystemWorker newSystemWorker() {
        return new TestableSystemWorker(
            mock(ApplicationEventPublisher.class),
            mock(WorkerJobExecutor.class),
            mock(WorkerJobFetcher.class),
            List.of(),
            mock(MaintenanceService.class),
            mock(MetricRegistry.class),
            mock(ServerConfig.class)
        );
    }

    /** Test seam that exposes the protected resolveWorkerGroup hook. */
    private static class TestableSystemWorker extends SystemWorker {

        TestableSystemWorker(
            ApplicationEventPublisher<ServiceStateChangeEvent> eventPublisher,
            WorkerJobExecutor workerJobExecutor,
            WorkerJobFetcher directQueueJobFetcher,
            List<QueueWorkerIOSender<?>> workerIOSenders,
            MaintenanceService maintenanceService,
            MetricRegistry metricRegistry,
            ServerConfig serverConfig) {
            super(
                eventPublisher, workerJobExecutor, directQueueJobFetcher,
                workerIOSenders, maintenanceService, metricRegistry, serverConfig
            );
        }

        String resolveWorkerGroupIdForTest() {
            return resolveWorkerGroupId();
        }
    }
}
