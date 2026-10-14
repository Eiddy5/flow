package org.flow.worker.systemworker;

import java.util.List;

import org.flow.core.contexts.FlowContext;
import org.flow.core.metrics.MetricRegistry;
import org.flow.core.server.ServerConfig;
import org.flow.core.server.ServiceStateChangeEvent;
import org.flow.core.server.ServiceType;
import org.flow.core.services.MaintenanceService;
import org.flow.core.worker.WorkerQueues;
import org.flow.worker.AbstractWorker;
import org.flow.worker.WorkerJobExecutor;
import org.flow.worker.fetchers.WorkerJobFetcher;
import org.flow.worker.senders.QueueWorkerIOSender;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventPublisher;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Worker variant hosted inside the executor / standalone process. Reuses
 * the full {@link AbstractWorker} pipeline (executor, IO senders, maintenance
 * integration) but with the {@link WorkerJobFetcher} on the inbound side
 * and {@link QueueWorkerIOSender} instances on the outbound side: no
 * network relay is needed. Liveness uses the shared service registry.
 * <p>
 * Does not implement {@link org.flow.core.runners.Worker} on purpose so it
 * does not collide with the regular {@link org.flow.worker.WorkerAgent}
 * singleton in STANDALONE mode where both run in the same JVM.
 * <p>
 * Registers as {@link ServiceType#SYSTEM_WORKER} rather than {@link ServiceType#WORKER}: the
 * {@link org.flow.core.server.ServiceRegistry} is keyed by service type, so sharing the type with
 * the {@link org.flow.worker.WorkerAgent} would make one evict the other in STANDALONE mode.
 */
@Singleton
@Requires(property = "flow.server-type", pattern = "(EXECUTOR|STANDALONE)")
@Slf4j
public class SystemWorker extends AbstractWorker implements org.flow.core.runners.SystemWorker {

    @Inject
    public SystemWorker(
        ApplicationEventPublisher<ServiceStateChangeEvent> eventPublisher,
        WorkerJobExecutor workerJobExecutor,
        WorkerJobFetcher directQueueJobFetcher,
        List<QueueWorkerIOSender<?>> workerIOSenders,
        MaintenanceService maintenanceService,
        MetricRegistry metricRegistry,
        ServerConfig serverConfig) {
        super(
            ServiceType.SYSTEM_WORKER,
            eventPublisher,
            workerJobExecutor,
            directQueueJobFetcher,
            workerIOSenders,
            maintenanceService,
            metricRegistry,
            serverConfig,
            "system-worker-io-"
        );
    }

    /**
     * Start with the default thread-pool size, pinned to the reserved
     * {@link WorkerQueues#SYSTEM_ID} Worker Queue. The pool size mirrors the
     * executor's sizing pattern but multiplied by 4 because SystemTasks are
     * I/O-bound (DB-backed purge / ship-out work).
     */
    public void start() {
        int threads = Math.max(4, FlowContext.getContext().getAllocatedCpuCores());
        start(threads);
    }

    /**
     * {@inheritDoc}
     * <p>
     * The SystemWorker exclusively serves the reserved {@link WorkerQueues#SYSTEM_ID}
     * Worker Queue.
     */
    @Override
    protected String resolveWorkerGroupId() {
        return WorkerQueues.SYSTEM_ID;
    }
}
