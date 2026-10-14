package org.flow.queue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import org.flow.core.metrics.MetricRegistry;
import org.flow.core.queues.QueueException;
import org.flow.core.queues.QueueSubscriber;
import org.flow.core.queues.VNodeDispatchQueueInterface;
import org.flow.core.queues.event.VNodeDispatchEvent;
import org.flow.core.utils.ExecutorsUtils;

import static org.flow.core.utils.Rethrow.throwFunction;

public abstract class AbstractVNodeDispatchQueue<T extends VNodeDispatchEvent> extends AbstractQueue<T> implements VNodeDispatchQueueInterface<T> {
    public AbstractVNodeDispatchQueue(Class<T> cls, QueueService queueService, ExecutorsUtils executorsUtils, MetricRegistry metricRegistry) {
        super(cls, queueService, executorsUtils, metricRegistry);
    }

    @Override
    public final void emit(T message) throws QueueException {
        this.doEmit(this.queueService.serialize(this.cls, message), message.key());

        listeners().forEach(l -> l.accept(message));
        this.emitCounter.increment();
    }

    @Override
    public final void emit(List<T> messages) throws QueueException {
        this.doEmit(
            messages.stream()
                .map(throwFunction(message -> new QueueRecord(message.key(), this.queueService.serialize(this.cls, message))))
                .toList()
        );

        listeners().forEach(l -> messages.forEach(l::accept));
        this.emitCounter.increment(messages.size());
    }

    @Override
    public CompletionStage<Void> emitAsync(T message) {
        return CompletableFuture.runAsync(() ->
        {
            try {
                emit(message);
            } catch (QueueException e) {
                throw new CompletionException(e);
            }
        }, asyncPoolExecutor);
    }

    @Override
    public CompletionStage<Void> emitAsync(List<T> messages) {
        return CompletableFuture.runAsync(() ->
        {
            try {
                emit(messages);
            } catch (QueueException e) {
                throw new CompletionException(e);
            }
        }, asyncPoolExecutor);
    }

    @Override
    public final QueueSubscriber<T> subscriber(Set<Integer> vNodes) {
        return trackSubscriber(doSubscriber(vNodes));
    }

    protected abstract QueueSubscriber<T> doSubscriber(Set<Integer> vNodes);

    protected abstract void doEmit(byte[] message, String key) throws QueueException;

    protected void doEmit(List<QueueRecord> messages) throws QueueException {
        for (QueueRecord message : messages) {
            doEmit(message.value(), message.key());
        }
    }
}
