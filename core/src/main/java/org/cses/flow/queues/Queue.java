package org.cses.flow.queues;

import java.util.concurrent.CompletionStage;

/**
 * Typed publishing boundary. Acceptance means transport acceptance, not business
 * completion. The application owns transport resources and consumer lifecycle.
 *
 * @param <T> message contract accepted by this queue
 */
public interface Queue<T> {

    /**
     * Returns the implementation-defined name of this Queue.
     * @return nonblank logical queue name
     */
    String queueName();

    /**
     * Publishes one message and waits for transport acceptance.
     * @param event nonnull message of the declared type
     * @throws QueueException when validation or publication fails
     */
    void emit(T event);

    /**
     * Publishes one message asynchronously.
     * @param event nonnull message of the declared type
     * @return transport-acceptance stage, failed with QueueException on rejection
     */
    CompletionStage<Void> emitAsync(T event);
}
