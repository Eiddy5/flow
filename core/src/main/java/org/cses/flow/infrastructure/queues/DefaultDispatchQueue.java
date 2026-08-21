package org.cses.flow.infrastructure.queues;

import org.cses.flow.infrastructure.queues.entries.QueueMessageEntry;
import org.cses.flow.queues.DispatchQueue;
import org.cses.flow.queues.QueueException;
import org.cses.flow.queues.QueueSubscription;
import org.cses.flow.queues.event.DispatchEvent;
import org.jooq.DSLContext;
import org.x9.jooq.JOOQ;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Default database-backed competing-consumer Queue.
 *
 * <p>Ordinary synchronous and asynchronous publishing uses a Queue-owned
 * transaction. {@link #emitInTransaction(DispatchEvent, DSLContext)} lets a
 * caller explicitly stage publication in its existing transaction; returning
 * from that method does not commit the caller-owned transaction.</p>
 *
 * <p>An external {@link #close()} waits for active Consumer calls. When a
 * Consumer of this Queue calls {@code close} itself, the call first stops all
 * subscriptions from taking more messages and then returns without waiting
 * for Consumer calls. This prevents two competing Consumers that close the
 * same Queue concurrently from waiting on each other. That Consumer call also
 * does not wait for already submitted asynchronous publishes; a later
 * external close still waits for their completion.</p>
 *
 * @param <T> business Event contract carried by this Queue
 */
public final class DefaultDispatchQueue<T extends DispatchEvent>
    implements DispatchQueue<T> {

    public static final long DEFAULT_POLL_INTERVAL_MILLIS = 100L;

    private final String queueName;
    private final PostgresQueueStore<T> store;
    private final long pollIntervalMillis;
    private final ExecutorService asyncExecutor;
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final Set<PollingQueueSubscription<T>> subscriptions =
        new LinkedHashSet<>();

    private boolean closed;

    public DefaultDispatchQueue(
        String queueName,
        JOOQ jooq,
        Class<T> eventType
    ) {
        this(
            queueName,
            jooq,
            eventType,
            DEFAULT_POLL_INTERVAL_MILLIS
        );
    }

    public DefaultDispatchQueue(
        String queueName,
        JOOQ jooq,
        Class<T> eventType,
        long pollIntervalMillis
    ) {
        if (queueName == null) {
            throw new IllegalArgumentException("queueName is required");
        }
        if (pollIntervalMillis <= 0) {
            throw new IllegalArgumentException(
                "pollIntervalMillis must be greater than zero"
            );
        }
        this.queueName = queueName;
        this.store = new PostgresQueueStore<>(
            queueName,
            jooq,
            Objects.requireNonNull(eventType, "eventType")
        );
        this.pollIntervalMillis = pollIntervalMillis;
        this.asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public String queueName() {
        return queueName;
    }

    @Override
    public void emit(T event) {
        requireOpen();
        T accepted = store.requireEvent(event);
        List<QueueMessageEntry> entries = store.prepare(accepted);
        store.publish(entries);
        signalAvailable();
    }

    @Override
    public void emitInTransaction(T event, DSLContext dsl) {
        requireOpen();
        T accepted = store.requireEvent(event);
        List<QueueMessageEntry> entries = store.prepare(accepted);
        store.publish(dsl, entries);
        signalAvailable();
    }

    @Override
    public void emit(List<T> events) {
        requireOpen();
        List<T> accepted = store.snapshot(events);
        List<QueueMessageEntry> entries = store.prepare(accepted);
        store.publish(entries);
        signalAvailable();
    }

    @Override
    public void emitInTransaction(List<T> events, DSLContext dsl) {
        requireOpen();
        List<T> accepted = store.snapshot(events);
        List<QueueMessageEntry> entries = store.prepare(accepted);
        store.publish(dsl, entries);
        signalAvailable();
    }

    @Override
    public CompletionStage<Void> emitAsync(T event) {
        try {
            requireOpen();
            List<QueueMessageEntry> entries = store.prepare(event);
            return submitAsync(entries);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(queueFailure(
                "Could not prepare asynchronous Queue publish",
                exception
            ));
        }
    }

    @Override
    public CompletionStage<Void> emitAsync(List<T> events) {
        try {
            requireOpen();
            List<T> accepted = store.snapshot(events);
            List<QueueMessageEntry> entries = store.prepare(accepted);
            return submitAsync(entries);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(queueFailure(
                "Could not prepare asynchronous Queue batch publish",
                exception
            ));
        }
    }

    @Override
    public QueueSubscription subscribe(Consumer<T> consumer) {
        if (consumer == null) {
            throw new QueueException("Consumer must not be null");
        }
        lifecycleLock.lock();
        try {
            requireOpenLocked();
            PollingQueueSubscription<T> subscription =
                new PollingQueueSubscription<>(
                    this,
                    store,
                    consumer,
                    pollIntervalMillis
                );
            subscriptions.add(subscription);
            subscription.start();
            return subscription;
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public void close() {
        List<PollingQueueSubscription<T>> active;
        lifecycleLock.lock();
        try {
            closed = true;
            active = new ArrayList<>(subscriptions);
        } finally {
            lifecycleLock.unlock();
        }

        boolean calledByWorker = active.stream()
            .anyMatch(PollingQueueSubscription::isWorkerThread);
        for (PollingQueueSubscription<T> subscription : active) {
            subscription.requestClose();
        }

        QueueException closeFailure = null;
        if (!calledByWorker) {
            for (PollingQueueSubscription<T> subscription : active) {
                try {
                    subscription.awaitTermination();
                } catch (QueueException exception) {
                    if (closeFailure == null) {
                        closeFailure = exception;
                    } else {
                        closeFailure.addSuppressed(exception);
                    }
                }
            }
        }
        if (calledByWorker) {
            asyncExecutor.shutdown();
        } else {
            asyncExecutor.close();
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    void onSubscriptionClosed(PollingQueueSubscription<T> subscription) {
        lifecycleLock.lock();
        try {
            subscriptions.remove(subscription);
        } finally {
            lifecycleLock.unlock();
        }
    }

    private CompletionStage<Void> submitAsync(
        List<QueueMessageEntry> entries
    ) {
        lifecycleLock.lock();
        try {
            if (closed) {
                return CompletableFuture.failedFuture(closedFailure());
            }
            return CompletableFuture.runAsync(() -> {
                store.publish(entries);
                signalAvailable();
            }, asyncExecutor);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(queueFailure(
                "Could not schedule asynchronous Queue publish",
                exception
            ));
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void requireOpen() {
        lifecycleLock.lock();
        try {
            requireOpenLocked();
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void requireOpenLocked() {
        if (closed) {
            throw closedFailure();
        }
    }

    private QueueException closedFailure() {
        return new QueueException("Default Queue is closed: " + queueName);
    }

    private QueueException queueFailure(
        String operation,
        RuntimeException cause
    ) {
        if (cause instanceof QueueException queueException) {
            return queueException;
        }
        return new QueueException(operation + ": " + queueName, cause);
    }

    private void signalAvailable() {
        List<PollingQueueSubscription<T>> active;
        lifecycleLock.lock();
        try {
            active = new ArrayList<>(subscriptions);
        } finally {
            lifecycleLock.unlock();
        }
        for (PollingQueueSubscription<T> subscription : active) {
            subscription.signalAvailable();
        }
    }
}
