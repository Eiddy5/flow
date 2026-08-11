package org.cses.flow.queues;

import org.cses.flow.queues.event.Event;

/**
 * Common identity and lifecycle contract for a typed Event Queue.
 * Implementations must be safe for concurrent use.
 *
 * @param <T> Event contract accepted by this Queue
 */
public interface Queue<T extends Event> extends AutoCloseable {

    /**
     * Returns the implementation-defined name of this Queue.
     */
    String queueName();

    /**
     * Idempotently closes this Queue and its active subscriptions. New publish
     * and subscribe operations fail with {@link QueueException} after close.
     */
    @Override
    void close();
}
