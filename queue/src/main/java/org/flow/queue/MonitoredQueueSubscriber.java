package org.flow.queue;

import java.util.List;
import java.util.function.Consumer;

import org.flow.core.exceptions.DeserializationException;
import org.flow.core.metrics.MetricRegistry;
import org.flow.core.queues.QueueSubscriber;
import org.flow.core.queues.event.Event;
import org.flow.core.utils.Either;

import io.micrometer.core.instrument.Counter;

/**
 * Decorator that records pause/resume metrics for a {@link QueueSubscriber} and
 * removes itself from its parent queue's tracking list on close.
 */
final class MonitoredQueueSubscriber<T extends Event> implements QueueSubscriber<T> {
    private final QueueSubscriber<T> delegate;
    private final AbstractQueue<T> queue;
    private final Counter pauseCounter;
    private final Counter resumeCounter;

    MonitoredQueueSubscriber(QueueSubscriber<T> delegate, AbstractQueue<T> queue, MetricRegistry metricRegistry) {
        this.delegate = delegate;
        this.queue = queue;
        this.pauseCounter = metricRegistry.counter(
            MetricRegistry.METRIC_QUEUE_SUBSCRIBERS_PAUSED_TOTAL,
            MetricRegistry.METRIC_QUEUE_SUBSCRIBERS_PAUSED_TOTAL_DESCRIPTION,
            MetricRegistry.TAG_QUEUE_NAME, queue.queueName()
        );
        this.resumeCounter = metricRegistry.counter(
            MetricRegistry.METRIC_QUEUE_SUBSCRIBERS_RESUMED_TOTAL,
            MetricRegistry.METRIC_QUEUE_SUBSCRIBERS_RESUMED_TOTAL_DESCRIPTION,
            MetricRegistry.TAG_QUEUE_NAME, queue.queueName()
        );
    }

    @Override
    public QueueSubscriber<T> subscribe(Consumer<Either<T, DeserializationException>> consumer) {
        delegate.subscribe(consumer);
        return this;
    }

    @Override
    public QueueSubscriber<T> subscribeBatch(Consumer<List<Either<T, DeserializationException>>> consumer) {
        delegate.subscribeBatch(consumer);
        return this;
    }

    @Override
    public void pause() {
        pauseCounter.increment();
        delegate.pause();
    }

    @Override
    public boolean isPaused() {
        return delegate.isPaused();
    }

    @Override
    public void resume() {
        resumeCounter.increment();
        delegate.resume();
    }

    @Override
    public void close() {
        try {
            delegate.close();
        } finally {
            queue.untrackSubscriber(this);
        }
    }

    @Override
    public boolean isActive() {
        return delegate.isActive();
    }
}
