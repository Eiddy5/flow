package org.flow.worker.fetchers;

import org.flow.core.scheduler.queue.TriggerEventQueue;
import org.flow.core.scheduler.events.TriggerReceived;
import org.flow.worker.services.WorkerJobErrorHandler;
import org.flow.worker.services.WorkerControlSubscription;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

import org.flow.core.executor.WorkerJobRunningStateStore;
import org.flow.core.queues.KeyedDispatchQueueInterface;
import org.flow.core.runners.*;
import org.flow.core.utils.IdUtils;
import org.flow.core.worker.WorkerQueues;
import org.flow.core.worker.models.WorkerContext;
import org.flow.worker.WorkerLoop;
import org.flow.worker.queues.WorkerQueue;
import org.flow.worker.queues.WorkerQueueRegistry;

import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;

/** Bounded, transactional queue intake. Each worker owns its own capacity ledger. */
@Prototype
public class WorkerJobFetcher extends WorkerLoop implements JobFetcher {
    private final KeyedDispatchQueueInterface<WorkerJobEvent> jobQueue;
    private final WorkerJobRunningStateStore runningJobs;
    private final WorkerQueueRegistry registry;
    private final TriggerEventQueue triggers;
    private final WorkerJobErrorHandler errors;
    private final WorkerControlSubscription controls;
    private final Map<String, Integer> inFlight = new ConcurrentHashMap<>();
    private final AtomicInteger inFlightCount = new AtomicInteger();
    private boolean controlsStarted;
    private WorkerQueue<WorkerJob> localQueue;
    private WorkerContext context;

    @Inject
    public WorkerJobFetcher(KeyedDispatchQueueInterface<WorkerJobEvent> jobQueue,
        WorkerJobRunningStateStore runningJobs, WorkerQueueRegistry registry,
        WorkerControlSubscription controls,
        WorkerJobErrorHandler errors,
        TriggerEventQueue triggers) {
        super(WorkerJobFetcher.class.getSimpleName());
        this.jobQueue = jobQueue;
        this.runningJobs = runningJobs;
        this.registry = registry;
        this.controls = controls;
        this.errors = errors;
        this.triggers = triggers;
    }

    @Override
    public synchronized void init(WorkerContext context) {
        if (this.context != null) {
            throw new IllegalStateException("Worker fetcher already initialized");
        }
        this.context = context;
        this.localQueue = registry.getOrCreate(context, WorkerJob.class);
        controls.start();
        controlsStarted = true;
    }

    @Override
    protected void doOnLoop() throws Exception {
        int capacity = Math.min(localQueue.remainingCapacity(),
            context.workerThreads() + context.jobBufferSize() - inFlightCount.get());
        if (capacity <= 0) {
            Thread.sleep(50);
            return;
        }
        try {
            var jobs = jobQueue.claim(WorkerQueues.toDispatchKey(context.workerGroupId()), capacity, (tx, decoded) -> {
                if (decoded.isRight()) {
                    errors.handle(decoded.getRight());
                    return false;
                }
                var event = decoded.getLeft();
                var owner = new WorkerInstance(context.workerId(), event.workerQueueId());
                WorkerJobRunning job = switch (event.job()) {
                    case WorkerTask task -> {
                        var running = WorkerTaskRunning.of(task, owner);
                        running.setDeliveryId(IdUtils.create());
                        task.setWorkerDeliveryId(running.getDeliveryId());
                        yield running;
                    }
                    case WorkerTrigger trigger -> WorkerTriggerRunning.of(trigger, owner);
                    default -> throw new IllegalArgumentException("Unsupported worker job: " + event.job().getClass());
                };
                boolean claimed = runningJobs.claim(tx, job);
                if (claimed && event.job() instanceof WorkerTrigger trigger) {
                    triggers.send(new TriggerReceived(trigger.triggerId(), context.workerId()));
                }
                return claimed;
            });
            // claim returns only after commit. A crash here leaves a recoverable running-job lease.
            for (var event : jobs) {
                inFlight.compute(event.job().uid(), (id, attempts) -> {
                    inFlightCount.incrementAndGet();
                    return attempts == null ? 1 : attempts + 1;
                });
                localQueue.put(event.job());
            }
            if (jobs.isEmpty()) {
                Thread.sleep(100);
            }
        } catch (RuntimeException e) {
            Thread.sleep(500);
            throw e;
        }
    }

    @Override
    public synchronized void close() {
        if (controlsStarted) {
            controls.release();
            controlsStarted = false;
        }
    }

    @Override
    public void onJobCompleted(String jobId) {
        inFlight.computeIfPresent(jobId, (id, attempts) -> {
            inFlightCount.decrementAndGet();
            return attempts == 1 ? null : attempts - 1;
        });
    }
}
