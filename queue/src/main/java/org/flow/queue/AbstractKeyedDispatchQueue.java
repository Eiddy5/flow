package org.flow.queue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import org.flow.core.metrics.MetricRegistry;
import org.flow.core.queues.KeyedDispatchQueueInterface;
import org.flow.core.queues.QueueException;
import org.flow.core.queues.QueueSubscriber;
import org.flow.core.queues.event.KeyedDispatchEvent;
import org.flow.core.utils.ExecutorsUtils;

import static org.flow.core.utils.Rethrow.throwFunction;

public abstract class AbstractKeyedDispatchQueue<T extends KeyedDispatchEvent> extends AbstractQueue<T> implements KeyedDispatchQueueInterface<T> {
    public AbstractKeyedDispatchQueue(Class<T> cls, QueueService queueService, ExecutorsUtils executorsUtils, MetricRegistry metricRegistry) {
        super(cls, queueService, executorsUtils, metricRegistry);
    }

    @Override
    public final void emit(String routingKey, T message) throws QueueException {
        this.doEmit(routingKey, this.queueService.serialize(this.cls, message), message.key());

        listeners().forEach(l -> l.accept(message));
        this.emitCounter.increment();
    }

    @Override
    public final void emit(String routingKey, List<T> messages) throws QueueException {
        this.doEmit(
            routingKey,
            messages.stream()
                .map(throwFunction(message -> new QueueRecord(message.key(), this.queueService.serialize(this.cls, message))))
                .toList()
        );

        listeners().forEach(l -> messages.forEach(l::accept));
        this.emitCounter.increment(messages.size());
    }

    @Override
    public CompletionStage<Void> emitAsync(String routingKey, T message) {
        return CompletableFuture.runAsync(() ->
        {
            try {
                emit(routingKey, message);
            } catch (QueueException e) {
                throw new CompletionException(e);
            }
        }, asyncPoolExecutor);
    }

    @Override
    public CompletionStage<Void> emitAsync(String routingKey, List<T> messages) {
        return CompletableFuture.runAsync(() ->
        {
            try {
                emit(routingKey, messages);
            } catch (QueueException e) {
                throw new CompletionException(e);
            }
        }, asyncPoolExecutor);
    }

    @Override
    public final QueueSubscriber<T> subscriber(String routingKey) {
        return trackSubscriber(doSubscriber(routingKey));
    }

    protected abstract QueueSubscriber<T> doSubscriber(String routingKey);

    protected abstract void doEmit(String routingKey, byte[] message, String key) throws QueueException;

    protected void doEmit(String routingKey, List<QueueRecord> messages) throws QueueException {
        for (QueueRecord message : messages) {
            doEmit(routingKey, message.value(), message.key());
        }
    }
}
