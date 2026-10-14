package org.flow.worker.services;

import org.flow.core.models.executions.ExecutionKilled;
import org.flow.core.queues.BroadcastQueueInterface;
import org.flow.core.queues.QueueSubscriber;
import jakarta.inject.Singleton;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PreDestroy;

/** One kill subscription per process, shared by ordinary and system workers. */
@Singleton
@Requires(property = "flow.server-type", pattern = "(WORKER|EXECUTOR|STANDALONE)")
public class WorkerControlSubscription {
    private final BroadcastQueueInterface<ExecutionKilled> queue;
    private final ExecutionKilledManager manager;
    private QueueSubscriber<ExecutionKilled> subscription;
    private int workers;

    public WorkerControlSubscription(BroadcastQueueInterface<ExecutionKilled> queue, ExecutionKilledManager manager) {
        this.queue = queue;
        this.manager = manager;
    }

    public synchronized void start() {
        if (subscription != null) {
            workers++;
            return;
        }
        subscription = queue.subscriber().subscribe(event -> {
            if (event.isRight()) {
                throw new IllegalStateException("Cannot deserialize worker kill event", event.getRight());
            }
            manager.onKillReceived(event.getLeft());
        });
        workers++;
    }

    public synchronized void release() {
        if (workers > 0 && --workers == 0) {
            close();
        }
    }

    @PreDestroy
    synchronized void close() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
            workers = 0;
        }
    }
}
