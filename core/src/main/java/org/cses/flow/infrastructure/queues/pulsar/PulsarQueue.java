package org.cses.flow.infrastructure.queues.pulsar;

import org.cses.flow.queues.QueueException;
import org.cses.flow.queues.Queue;
import org.paas.pulsar.Pulsar;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Typed publishing over a PAAS-owned Pulsar instance. Successful publication means
 * broker acceptance, not completed consumption. No database transaction, atomic
 * batch, or independent resource-close contract is exposed.
 * @param <T> annotated message type
 */
public class PulsarQueue<T> implements Queue<T> {
    private String name;
    private Class<T> messageType;
    private Pulsar<T> pulsar;

    /**
     * Creates a publisher without taking ownership of the shared PAAS resource.
     * @param name validated logical name
     * @param messageType validated message class
     * @param pulsar shared, nonnull PAAS topic instance
     */
    PulsarQueue(String name, Class<T> messageType, Pulsar<T> pulsar) {
        this.name = name;
        this.messageType = messageType;
        this.pulsar = Objects.requireNonNull(pulsar, "pulsar");
    }

    /**
     * Identifies this publisher without exposing the underlying PAAS resource.
     * @return the logical name declared on the message class
     */
    @Override
    public String queueName() {
        return name;
    }

    /**
     * Publishes one message and waits for broker acceptance.
     * @param event nonnull message of the declared type
     * @throws QueueException when validation, encoding or publishing fails
     */
    @Override
    public void emit(T event) {
        try {
            pulsar.send(requireEvent(event));
        } catch (Exception exception) {
            throw failure(exception);
        }
    }

    /**
     * Publishes one message with a routing key and waits for broker acceptance.
     * @param event nonnull message of the declared type
     * @param key nonblank routing key; not a business deduplication guarantee
     * @throws QueueException when validation, encoding or publishing fails
     */
    public void emitWithKey(T event, String key) {
        try {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Message key must not be blank");
            }
            pulsar.sendWithKey(requireEvent(event), key);
        } catch (Exception exception) {
            throw failure(exception);
        }
    }

    /**
     * Starts one publication and reports both immediate and asynchronous failures through the stage.
     * @param event nonnull message of the declared type
     * @return broker-acceptance stage; failure has a QueueException cause
     */
    @Override
    public CompletionStage<Void> emitAsync(T event) {
        try {
            return pulsar.sendAsync(requireEvent(event)).handle((messageId, error) -> {
                if (error != null) {
                    throw failure(error);
                }
                return null;
            });
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(failure(exception));
        }
    }

    /**
     * Publishes a message for delivery no earlier than the specified timestamp.
     * @param event nonnull message of the declared type
     * @param timestampMillis nonnegative UTC Unix timestamp in milliseconds; past times are immediately eligible
     * @throws QueueException when validation, encoding or publishing fails
     */
    public void emitAt(T event, long timestampMillis) {
        try {
            if (timestampMillis < 0) {
                throw new IllegalArgumentException("Delivery timestamp must not be negative");
            }
            pulsar.sendAtTime(requireEvent(event), timestampMillis);
        } catch (Exception exception) {
            throw failure(exception);
        }
    }

    /**
     * Checks null and raw-generic misuse before handing a message to PAAS.
     * @param event caller-supplied value
     * @return the same typed, nonnull value
     * @throws RuntimeException when the value is null or has the wrong type
     */
    private T requireEvent(T event) {
        return messageType.cast(Objects.requireNonNull(event, "event"));
    }

    /**
     * Adds queue identity to an underlying publication failure.
     * @param cause original failure
     * @return unchecked queue failure retaining its cause
     */
    private QueueException failure(Throwable cause) {
        return new QueueException("Pulsar publication failed for queue: " + name, cause);
    }
}
