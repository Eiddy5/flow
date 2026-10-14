package org.flow.core.scheduler.queue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.flow.core.exceptions.FlowRuntimeException;
import org.flow.core.queues.BroadcastQueueInterface;
import org.flow.core.queues.QueueException;
import org.flow.core.queues.QueueSubscriber;
import org.flow.core.scheduler.events.SchedulerEvent;
import org.flow.core.utils.Disposable;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Default implementation of {@link SchedulerEventQueue} using a broadcast queue.
 */
@Singleton
public class DefaultSchedulerEventQueue implements SchedulerEventQueue {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultSchedulerEventQueue.class);

    private final BroadcastQueueInterface<SchedulerEvent> schedulerEventQueue;

    private final List<Disposable> subscribers = new ArrayList<>();

    @Inject
    public DefaultSchedulerEventQueue(final BroadcastQueueInterface<SchedulerEvent> schedulerEventQueue) {
        this.schedulerEventQueue = schedulerEventQueue;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void send(SchedulerEvent event) {
        try {
            schedulerEventQueue.emit(event);
        } catch (QueueException e) {
            throw new FlowRuntimeException("Unexpected error while publishing scheduler-event", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Disposable subscribe(Consumer<SchedulerEvent> consumer) {
        QueueSubscriber<SchedulerEvent> subscriber = schedulerEventQueue.subscriber();
        Disposable disposable = Disposable.of(subscriber::close);
        subscribers.add(disposable);
        subscriber.subscribe(either ->
        {
            either.fold(
                Optional::ofNullable,
                e ->
                {
                    LOG.warn("Failed to deserialize event. Cause: {}", e.getMessage());
                    return Optional.<SchedulerEvent> empty();
                }
            ).ifPresent(consumer);
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
