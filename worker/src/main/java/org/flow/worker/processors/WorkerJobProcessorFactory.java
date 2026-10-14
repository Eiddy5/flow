package org.flow.worker.processors;

import org.flow.core.metrics.MetricRegistry;
import org.flow.core.models.executions.LogEntry;
import org.flow.core.models.executions.MetricEntry;
import org.flow.core.runners.RunContextInitializer;
import org.flow.core.runners.RunContextLoggerFactory;
import org.flow.core.runners.Worker;
import org.flow.core.runners.WorkerJob;
import org.flow.core.runners.WorkerTask;
import org.flow.core.runners.WorkerTaskResult;
import org.flow.core.runners.WorkerTrigger;
import org.flow.core.server.ServerConfig;
import org.flow.core.trace.Tracer;
import org.flow.core.trace.TracerFactory;
import org.flow.core.worker.models.WorkerContext;
import org.flow.core.worker.models.WorkerTriggerResult;
import org.flow.worker.WorkerConfig;
import org.flow.worker.WorkerSecurityService;
import org.flow.worker.queues.WorkerQueueRegistry;
import org.flow.worker.services.ExecutionKilledManager;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
public class WorkerJobProcessorFactory {

    @Inject
    private MetricRegistry metricRegistry;
    @Inject
    private WorkerSecurityService workerSecurityService;
    @Inject
    private RunContextInitializer runContextInitializer;
    @Inject
    private RunContextLoggerFactory runContextLoggerFactory;
    @Inject
    private ServerConfig serverConfig;
    // QUEUES
    @Inject
    private WorkerQueueRegistry workerQueueRegistry;

    @Inject
    private ExecutionKilledManager executionKilledManager;

    @Inject
    private WorkerConfig workerConfig;

    @Inject
    private TracerFactory tracerFactory;
    private Tracer tracer;

    @PostConstruct
    public void init() {
        this.tracer = tracerFactory.getTracer(Worker.class, "WORKER");
    }

    @SuppressWarnings("unchecked")
    public <T extends WorkerJob> WorkerJobProcessor<T> create(WorkerContext context, T job) {
        if (job instanceof WorkerTask task) {
            return (WorkerJobProcessor<T>) new WorkerTaskProcessor(
                context.workerId(),
                context.workerGroupId(),
                serverConfig,
                metricRegistry,
                workerSecurityService,
                tracer,
                runContextInitializer,
                runContextLoggerFactory,
                new org.flow.worker.queues.AbstractDelegateWorkerQueue<WorkerTaskResult>(
                    workerQueueRegistry.getOrCreate(context, WorkerTaskResult.class)) {
                    @Override
                    public void put(WorkerTaskResult result) {
                        super.put(result.withWorkerJobId(job.uid()).withWorkerDeliveryId(task.getWorkerDeliveryId()));
                    }
                },
                workerQueueRegistry.getOrCreate(context, MetricEntry.class),
                executionKilledManager
            );
        } else if (job instanceof WorkerTrigger) {
            return (WorkerJobProcessor<T>) new WorkerTriggerProcessor(
                context.workerGroupId(),
                metricRegistry,
                workerSecurityService,
                tracer,
                runContextInitializer,
                workerQueueRegistry.getOrCreate(context, LogEntry.class),
                workerQueueRegistry.getOrCreate(context, WorkerTriggerResult.class),
                executionKilledManager,
                workerConfig.pollingTriggerTimeout()
            );
        }

        throw new IllegalArgumentException("Unsupported worker job type [" + job.getClass().getName() + "]");
    }

}
