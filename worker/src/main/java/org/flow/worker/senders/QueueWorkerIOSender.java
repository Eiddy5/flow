package org.flow.worker.senders;

import java.time.Duration;
import java.util.function.BiConsumer;

import org.flow.core.worker.models.WorkerContext;
import org.flow.worker.WorkerLoop;
import org.flow.worker.queues.WorkerQueue;
import org.flow.worker.queues.WorkerQueueRegistry;

/** Retains the current event until its durable publication succeeds. */
public class QueueWorkerIOSender<T> extends WorkerLoop implements WorkerIOSender {
    private final WorkerQueueRegistry registry;
    private final Class<T> eventType;
    private final BiConsumer<WorkerContext, T> publisher;
    private WorkerContext context;
    private WorkerQueue<T> queue;
    private volatile T pending;

    public QueueWorkerIOSender(WorkerQueueRegistry registry, Class<T> eventType,
        BiConsumer<WorkerContext, T> publisher) {
        super(eventType.getSimpleName() + "QueueSender");
        this.registry = registry;
        this.eventType = eventType;
        this.publisher = publisher;
    }

    @Override
    public synchronized void init(WorkerContext context) {
        this.context = context;
        this.queue = registry.getOrCreate(context, eventType);
    }

    @Override
    protected void doOnLoop() throws Exception {
        try {
            sendNext(Duration.ofMillis(100));
        } catch (RuntimeException e) {
            Thread.sleep(500);
            throw e;
        }
    }

    private boolean sendNext(Duration timeout) throws InterruptedException {
        if (pending == null) {
            pending = queue.poll(timeout);
        }
        if (pending == null) {
            return false;
        }
        publisher.accept(context, pending);
        pending = null;
        return true;
    }

    @Override
    protected void cleanup() throws InterruptedException {
        boolean interrupted = Thread.interrupted();
        try {
            // Stop after the first failure; hasUndeliveredResults prevents a false graceful stop.
            while (sendNext(Duration.ZERO)) {
                // Drain already-produced results before closing the worker.
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean hasUndeliveredResults() {
        return pending != null || (queue != null && queue.size() > 0);
    }
}
