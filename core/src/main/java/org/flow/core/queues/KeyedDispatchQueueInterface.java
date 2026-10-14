package org.flow.core.queues;

import org.flow.core.utils.Either;
import org.flow.core.exceptions.DeserializationException;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.BiPredicate;

import org.flow.core.runners.TransactionContext;

public interface KeyedDispatchQueueInterface<T extends org.flow.core.queues.event.KeyedDispatchEvent> extends GenericQueueInterface<T> {
    void emit(String routingKey, T message) throws QueueException;

    void emit(String routingKey, List<T> messages) throws QueueException;

    CompletionStage<Void> emitAsync(String routingKey, T message);

    CompletionStage<Void> emitAsync(String routingKey, List<T> messages);

    QueueSubscriber<T> subscriber(String routingKey);

    /**
     * Claim at most {@code limit} messages. Preparation and acknowledgement share a
     * transaction; accepted messages are returned only after commit. Returning false
     * discards a duplicate or stale message. Throwing rolls the entire batch back.
     * The callback must not execute jobs or publish to an in-memory queue.
     */
    List<T> claim(String routingKey, int limit, BiPredicate<TransactionContext, Either<T, DeserializationException>> prepare);

    Integer queueLag(String routingKey);
}
