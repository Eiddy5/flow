package org.cses.flow.queues;

import org.cses.flow.queues.event.DispatchEvent;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Typed Queue whose active subscriptions compete for each Event.
 *
 * <p>A Dispatch Queue does not guarantee processing order. Publishing and
 * business processing are independent: a successful emit only means that the
 * Queue accepted responsibility for delivering the Event. While the Queue is
 * open, an accepted Event remains eligible for a later subscription when no
 * Consumer is active.</p>
 *
 * <p>When an implementation joins a caller-owned transaction, Queue
 * acceptance takes effect only if that transaction commits.</p>
 *
 * <p>A Consumer acknowledges an Event only by returning normally. If it
 * throws, the delivery transaction rolls back and the Event remains eligible
 * for retry.</p>
 *
 * @param <T> competing-consumer Event contract accepted by this Queue
 */
public interface DispatchQueue<T extends DispatchEvent> extends Queue<T> {

    /**
     * Emits one Event.
     *
     * @param event Event to emit
     * @throws QueueException when the Queue cannot accept the Event
     */
    void emit(T event);

    /**
     * Atomically emits a batch of Events.
     *
     * @param events Events to emit
     * @throws QueueException when the Queue cannot accept the complete batch
     */
    void emit(List<T> events);

    /**
     * Asynchronously emits one Event.
     *
     * @param event Event to emit
     * @return completion of Queue acceptance, not business processing; the
     *         stage fails with {@link QueueException} when acceptance fails
     */
    CompletionStage<Void> emitAsync(T event);

    /**
     * Asynchronously and atomically emits a batch of Events.
     *
     * @param events Events to emit
     * @return completion of complete-batch Queue acceptance; the stage fails
     *         with {@link QueueException} when acceptance fails
     */
    CompletionStage<Void> emitAsync(List<T> events);

    /**
     * Immediately registers one serial Consumer. Multiple active
     * subscriptions compete for Events emitted to this Queue.
     *
     * @param consumer Event Consumer
     * @return active subscription controlling this registration
     * @throws QueueException when the Consumer cannot be registered
     */
    QueueSubscription subscribe(Consumer<T> consumer);
}
