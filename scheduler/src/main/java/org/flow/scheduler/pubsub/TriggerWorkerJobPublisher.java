package org.flow.scheduler.pubsub;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import org.flow.core.exceptions.InternalException;
import org.flow.core.exceptions.NoMatchingWorkerQueueException;
import org.flow.core.models.conditions.ConditionContext;
import org.flow.core.models.flows.FlowInterface;
import org.flow.core.models.triggers.AbstractTrigger;
import org.flow.core.queues.KeyedDispatchQueueInterface;
import org.flow.core.queues.QueueException;
import org.flow.core.runners.RunContext;
import org.flow.core.runners.WorkerJobEvent;
import org.flow.core.runners.WorkerQueueRouting;
import org.flow.core.runners.WorkerTrigger;
import org.flow.core.runners.WorkerTriggerData;
import org.flow.core.scheduler.model.TriggerState;
import org.flow.core.services.WorkerQueueService;
import org.flow.core.utils.Logs;
import org.flow.core.worker.WorkerQueues;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

//TODO should not be part of the scheduler
@Singleton
public class TriggerWorkerJobPublisher {

    private static final Logger log = LoggerFactory.getLogger(TriggerWorkerJobPublisher.class);

    private final WorkerQueueService workerQueueService;
    private final KeyedDispatchQueueInterface<WorkerJobEvent> workerJobEventQueue;

    @Inject
    public TriggerWorkerJobPublisher(
        WorkerQueueService workerQueueService,
        KeyedDispatchQueueInterface<WorkerJobEvent> workerJobEventQueue) {
        this.workerQueueService = workerQueueService;
        this.workerJobEventQueue = workerJobEventQueue;
    }

    /**
     * Sends the given trigger to a worker queue for evaluation.
     *
     * @return {@code true} if the trigger was dispatched to a worker queue; {@code false} if it was
     *         dropped (no matching worker queue, no available worker, or queue error).
     */
    public boolean send(TriggerState triggerState, AbstractTrigger trigger, FlowInterface flow, ConditionContext conditionContext) throws InternalException {

        if (log.isDebugEnabled()) {
            Logs.logTrigger(
                triggerState,
                Level.DEBUG,
                "[date: {}] Scheduling evaluation to the worker",
                triggerState.getEvaluatedAt()
            );
        }

        WorkerTrigger workerTrigger = WorkerTrigger
            .builder()
            .trigger(trigger)
            .data(WorkerTriggerData.from(conditionContext, triggerState.context()))
            .dispatchEpoch(triggerState.getDispatchEpoch())
            .build();
        Optional<WorkerQueueRouting> routing;
        try {
            routing = workerQueueService.resolveWorkerQueueForJob(flow, workerTrigger);
        } catch (NoMatchingWorkerQueueException e) {
            // No Worker Queue matches the requested tags — a configuration error.
            // Drop the trigger evaluation with a clear, user-facing log message.
            conditionContext.getRunContext().logger()
                .error("{}, ignoring the trigger.", e.getMessage());
            return false;
        }
        try {
            if (routing.isEmpty() || routing.get().isDefault()) {
                // No routing or explicit default Worker Queue — dispatch with null key.
                this.workerJobEventQueue.emit(null, WorkerJobEvent.of(workerTrigger, null));
                return true;
            }
            WorkerQueueRouting r = routing.get();
            String workerQueueId = r.workerQueueId();
            String workerQueueForLog = WorkerQueues.forLog(r.tags(), workerQueueId);
            RunContext runContext = conditionContext.getRunContext();
            switch (r.disposition()) {
                case DISPATCH -> {
                    this.workerJobEventQueue.emit(workerQueueId, WorkerJobEvent.of(workerTrigger, workerQueueId));
                    return true;
                }
                case WAIT_AND_DISPATCH -> {
                    runContext.logger()
                        .info("No workers are available for {}, waiting for one to be available.", workerQueueForLog);
                    this.workerJobEventQueue.emit(workerQueueId, WorkerJobEvent.of(workerTrigger, workerQueueId));
                    return true;
                }
                case FAIL -> runContext.logger()
                    .error("No workers are available for {}, ignoring the trigger.", workerQueueForLog);
                case CANCEL -> runContext.logger()
                    .warn("No workers are available for {}, ignoring the trigger.", workerQueueForLog);
            }
        } catch (QueueException e) {
            log.error("Unable to emit the Worker Trigger job", e);
        }
        return false;
    }
}
