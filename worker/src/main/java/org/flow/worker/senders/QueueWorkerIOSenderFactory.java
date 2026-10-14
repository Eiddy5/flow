package org.flow.worker.senders;

import org.flow.core.runners.RunContextLoggerFactory;
import org.flow.core.scheduler.model.TriggerType;
import org.flow.core.queues.event.DispatchEvent;

import org.flow.core.executor.WorkerJobRunningStateStore;
import org.flow.core.models.executions.LogEntry;
import org.flow.core.models.executions.MetricEntry;
import org.flow.core.models.flows.State;
import org.flow.core.queues.*;
import org.flow.core.runners.WorkerTaskResult;
import org.flow.core.scheduler.events.TriggerEvaluated;
import org.flow.core.scheduler.events.TriggerExecutionTerminated;
import org.flow.core.scheduler.queue.TriggerEventQueue;
import org.flow.core.scheduler.service.TriggerExecutionPublisher;
import org.flow.core.worker.models.WorkerTriggerResult;
import org.flow.core.worker.models.WorkerContext;
import org.flow.worker.queues.WorkerQueueRegistry;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Prototype;

/** Ordinary and system workers receive separate sender instances and local queues. */
@Factory
public class QueueWorkerIOSenderFactory {
    @Prototype
    public QueueWorkerIOSender<WorkerTaskResult> taskResults(WorkerQueueRegistry registry,
        WorkerJobRunningStateStore jobs, DispatchQueueInterface<WorkerTaskResult> queue,
        RunContextLoggerFactory loggers) {
        return new QueueWorkerIOSender<>(registry, WorkerTaskResult.class, (context, result) -> {
            try {
                publishTask(jobs, queue, context, result);
            } catch (InvalidResultException invalid) {
                loggers.create(result).logger().error("Unable to emit the worker task result to the queue: {}",
                    invalid.getCause().getMessage(), invalid.getCause());
                // Payload failures cannot heal with a retry. Publish a failed result without outputs.
                publishTask(jobs, queue, context, new WorkerTaskResult(result.getTaskRun().fail(), null)
                    .withWorkerJobId(result.getWorkerJobId()).withWorkerDeliveryId(result.getWorkerDeliveryId()));
            }
        });
    }

    private static void publishTask(WorkerJobRunningStateStore jobs, DispatchQueueInterface<WorkerTaskResult> queue,
        WorkerContext context, WorkerTaskResult result) {
        String jobId = result.getWorkerJobId() == null ? result.uid() : result.getWorkerJobId();
        jobs.publishResult(jobId, context.workerId(), result.getWorkerDeliveryId(), null,
            jobId.equals(result.uid()) && result.getTaskRun().getState().isTerminated(),
            result.completionId(), () -> {
            try {
                queue.emit(result);
            } catch (MessageTooBigException | UnsupportedMessageException e) {
                throw new InvalidResultException(e);
            } catch (QueueException e) {
                throw new IllegalStateException("Unable to publish task result", e);
            }
        });
    }

    @Prototype
    public QueueWorkerIOSender<WorkerTriggerResult> triggerResults(WorkerQueueRegistry registry,
        WorkerJobRunningStateStore jobs, TriggerEventQueue queue, TriggerExecutionPublisher publisher) {
        return new QueueWorkerIOSender<>(registry, WorkerTriggerResult.class, (context, result) -> {
            boolean terminal = result.type() == TriggerType.POLLING ||
                result.evaluation() == null || State.Type.FAILED.equals(result.evaluation().stateType());
            jobs.publishResult(result.id().uid(), context.workerId(), null, result.dispatchEpoch(), terminal, null, () -> {
                switch (result.type()) {
                    case POLLING -> queue.send(new TriggerEvaluated(result.id(), result.evaluation()));
                    case REALTIME -> {
                        if (result.evaluation() == null) {
                            queue.send(new TriggerExecutionTerminated(result.id(), null, State.Type.FAILED, result.dispatchEpoch()));
                        } else {
                            publisher.send(result.id(), result.evaluation());
                        }
                    }
                }
            });
        });
    }

    @Prototype
    public QueueWorkerIOSender<LogEntry> logs(WorkerQueueRegistry registry, DispatchQueueInterface<LogEntry> queue) {
        return new QueueWorkerIOSender<>(registry, LogEntry.class, (context, event) -> emit(queue, event));
    }

    @Prototype
    public QueueWorkerIOSender<MetricEntry> metrics(WorkerQueueRegistry registry, DispatchQueueInterface<MetricEntry> queue) {
        return new QueueWorkerIOSender<>(registry, MetricEntry.class, (context, event) -> emit(queue, event));
    }

    private static <T extends DispatchEvent> void emit(DispatchQueueInterface<T> queue, T event) {
        try {
            queue.emit(event);
        } catch (QueueException e) {
            throw new IllegalStateException("Unable to publish worker event", e);
        }
    }

    private static class InvalidResultException extends RuntimeException {
        InvalidResultException(QueueException cause) {
            super(cause);
        }
    }
}
