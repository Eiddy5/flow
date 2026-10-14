package org.flow.core.scheduler.queue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.flow.core.exceptions.FlowRuntimeException;
import org.flow.core.queues.QueueException;
import org.flow.core.queues.QueueSubscriber;
import org.flow.core.queues.VNodeDispatchQueueInterface;
import org.flow.core.scheduler.SchedulerConfiguration;
import org.flow.core.scheduler.events.TriggerEvent;
import org.flow.core.scheduler.vnodes.VNodes;
import org.flow.core.utils.Disposable;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Default implementation of {@link TriggerEventQueue} using a virtual node dispatch queue.
 */
@Singleton
public class DefaultTriggerEventQueue implements TriggerEventQueue {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultTriggerEventQueue.class);

    private final VNodeDispatchQueueInterface<TriggerEvent> triggerEventQueue;
    private final SchedulerConfiguration schedulerConfiguration;

    private final List<Disposable> subscribers = new ArrayList<>();

    @Inject
    public DefaultTriggerEventQueue(VNodeDispatchQueueInterface<TriggerEvent> triggerEventQueue,
        SchedulerConfiguration schedulerConfiguration) {
        this.triggerEventQueue = triggerEventQueue;
        this.schedulerConfiguration = schedulerConfiguration;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void send(TriggerEvent triggerEvent) {
        try {
            triggerEventQueue.emit(triggerEvent);
        } catch (QueueException e) {
            throw new FlowRuntimeException("Unexpected error while publishing trigger-event", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Disposable subscribe(Set<Integer> vNodes, BatchRecordConsumer consumer) {
        QueueSubscriber<TriggerEvent> subscriber = triggerEventQueue.subscriber(vNodes);
        Disposable disposable = Disposable.of(subscriber::close);
        subscribers.add(disposable);
        subscriber.subscribe(either ->
        {
            Optional<TriggerEvent> optional = either.fold(
                Optional::ofNullable,
                e ->
                {
                    LOG.warn("Failed to deserialize event. Cause: {}", e.getMessage());
                    return Optional.empty();
                }
            );
            //TODO - rework querying API to support both batch and vNode aware consumption.
            optional.ifPresent(event ->
            {
                int vNode = VNodes.computeVNode(schedulerConfiguration.vnodes(), event.key());
                consumer.accept(vNode, List.of(event));
            });
        });
        return disposable;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        Disposable.of(subscribers).dispose();
    }
}
