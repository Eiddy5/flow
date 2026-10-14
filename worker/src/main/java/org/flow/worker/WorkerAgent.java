package org.flow.worker;

import java.util.List;

import org.flow.core.metrics.MetricRegistry;
import org.flow.core.runners.Worker;
import org.flow.core.server.ServerConfig;
import org.flow.core.server.ServiceStateChangeEvent;
import org.flow.core.server.ServiceType;
import org.flow.core.services.MaintenanceService;
import org.flow.worker.fetchers.WorkerJobFetcher;
import org.flow.worker.senders.QueueWorkerIOSender;
import org.flow.core.worker.WorkerGroups;

import io.micronaut.context.event.ApplicationEventPublisher;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Default worker process. Reuses the lifecycle and IO-sender plumbing from
 * {@link AbstractWorker} and directly claims jobs from the worker
 * queue using the configured JDBC backend.
 */
@Slf4j
@Singleton
public class WorkerAgent extends AbstractWorker implements Worker {

    private final WorkerConfig workerConfig;

    @Inject
    public WorkerAgent(
        ApplicationEventPublisher<ServiceStateChangeEvent> eventPublisher,
        WorkerJobExecutor workerJobExecutor,
        WorkerJobFetcher workerJobFetcher,
        List<QueueWorkerIOSender<?>> workerIOSenders,
        MaintenanceService maintenanceService,
        MetricRegistry metricRegistry,
        ServerConfig serverConfig,
        WorkerConfig workerConfig) {
        super(
            ServiceType.WORKER,
            eventPublisher,
            workerJobExecutor,
            workerJobFetcher,
            workerIOSenders,
            maintenanceService,
            metricRegistry,
            serverConfig,
            "worker-io-"
        );
        this.workerConfig = workerConfig;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Uses the default Worker Queue in the core distribution:
     * {@link org.flow.core.worker.WorkerGroups#DEFAULT_ID}.
     */
    @Override
    protected String resolveWorkerGroupId() {
        return WorkerGroups.DEFAULT_ID;
    }

    @Override
    protected int jobBufferSize(int numThreads) {
        Integer configured = workerConfig.jobBufferSize();
        if (configured == null) {
            return numThreads;
        }
        if (configured > numThreads) {
            log.warn("The configured job buffer size {} exceeds the {} worker threads, using {} instead.", configured, numThreads, numThreads);
            return numThreads;
        }
        return configured;
    }
}
