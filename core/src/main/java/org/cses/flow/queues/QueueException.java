package org.cses.flow.queues;

/**
 * Runtime failure reported by a Queue implementation.
 */
public final class QueueException extends RuntimeException {

    public QueueException(String message) {
        super(message);
    }

    public QueueException(String message, Throwable cause) {
        super(message, cause);
    }
}
