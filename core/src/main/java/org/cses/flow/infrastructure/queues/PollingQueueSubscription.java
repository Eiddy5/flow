package org.cses.flow.infrastructure.queues;

import org.cses.flow.queues.QueueException;
import org.cses.flow.queues.QueueSubscription;
import org.cses.flow.queues.event.DispatchEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * One serial periodic-polling registration on a Default Dispatch Queue.
 */
final class PollingQueueSubscription<T extends DispatchEvent>
    implements QueueSubscription {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        PollingQueueSubscription.class
    );

    private final DefaultDispatchQueue<T> owner;
    private final PostgresQueueStore<T> store;
    private final Consumer<T> consumer;
    private final long pollIntervalMillis;
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final Condition wakeUp = lifecycleLock.newCondition();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch terminated = new CountDownLatch(1);

    private volatile boolean paused;
    private volatile Thread worker;

    PollingQueueSubscription(
        DefaultDispatchQueue<T> owner,
        PostgresQueueStore<T> store,
        Consumer<T> consumer,
        long pollIntervalMillis
    ) {
        this.owner = owner;
        this.store = store;
        this.consumer = consumer;
        this.pollIntervalMillis = pollIntervalMillis;
    }

    void start() {
        Thread newWorker = Thread.ofVirtual()
            .name("flow-default-queue-" + owner.queueName())
            .unstarted(this::poll);
        worker = newWorker;
        newWorker.start();
    }

    void signalAvailable() {
        lifecycleLock.lock();
        try {
            wakeUp.signalAll();
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public void pause() {
        lifecycleLock.lock();
        try {
            if (!closed.get()) {
                paused = true;
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public boolean isPaused() {
        return !closed.get() && paused;
    }

    @Override
    public void resume() {
        lifecycleLock.lock();
        try {
            if (!closed.get()) {
                paused = false;
                wakeUp.signalAll();
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public boolean isActive() {
        return worker != null && !closed.get();
    }

    @Override
    public void close() {
        requestClose();
        if (!isWorkerThread()) {
            awaitTermination();
        }
    }

    void requestClose() {
        closed.set(true);
        lifecycleLock.lock();
        try {
            paused = false;
            wakeUp.signalAll();
        } finally {
            lifecycleLock.unlock();
        }
    }

    boolean isWorkerThread() {
        return Thread.currentThread() == worker;
    }

    void awaitTermination() {
        try {
            terminated.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QueueException(
                "Interrupted while closing Queue subscription",
                exception
            );
        }
    }

    private void poll() {
        try {
            while (!closed.get()) {
                if (!awaitReady()) {
                    return;
                }
                try {
                    PostgresQueueStore.DeliveryAttempt attempt = deliverOne();
                    if (attempt == null) {
                        continue;
                    }
                    if (!attempt.delivered()) {
                        awaitNextPoll();
                    }
                } catch (QueueException exception) {
                    LOGGER.error(
                        "Queue delivery transaction failed; message remains "
                            + "pending. queue={}",
                        owner.queueName(),
                        exception
                    );
                    awaitNextPoll();
                }
            }
        } finally {
            closed.set(true);
            terminated.countDown();
            owner.onSubscriptionClosed(this);
        }
    }

    private PostgresQueueStore.DeliveryAttempt deliverOne() {
        lifecycleLock.lock();
        try {
            if (closed.get() || paused) {
                return null;
            }
        } finally {
            lifecycleLock.unlock();
        }
        return store.deliverOne(consumer, this::deliveryAllowed);
    }

    private boolean deliveryAllowed() {
        lifecycleLock.lock();
        try {
            return !closed.get() && !paused;
        } finally {
            lifecycleLock.unlock();
        }
    }

    private boolean awaitReady() {
        lifecycleLock.lock();
        try {
            while (paused && !closed.get()) {
                wakeUp.await();
            }
            return !closed.get();
        } catch (InterruptedException exception) {
            closed.set(true);
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void awaitNextPoll() {
        lifecycleLock.lock();
        try {
            if (!closed.get() && !paused) {
                wakeUp.await(
                    pollIntervalMillis,
                    TimeUnit.MILLISECONDS
                );
            }
        } catch (InterruptedException exception) {
            closed.set(true);
            Thread.currentThread().interrupt();
        } finally {
            lifecycleLock.unlock();
        }
    }
}
