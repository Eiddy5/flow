package org.flow.worker.services;

import org.flow.core.models.executions.ExecutionKilled;
import org.flow.core.queues.BroadcastQueueInterface;
import org.flow.core.queues.QueueSubscriber;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkerControlSubscriptionTest {
    @Test
    @SuppressWarnings("unchecked")
    void sharesSubscriptionUntilLastWorkerStops() {
        var queue = (BroadcastQueueInterface<ExecutionKilled>) mock(BroadcastQueueInterface.class);
        var subscriber = (QueueSubscriber<ExecutionKilled>) mock(QueueSubscriber.class);
        when(queue.subscriber()).thenReturn(subscriber);
        when(subscriber.subscribe(any())).thenReturn(subscriber);
        var controls = new WorkerControlSubscription(queue, mock(ExecutionKilledManager.class));
        controls.start();
        controls.start();
        verify(queue, times(1)).subscriber();
        controls.release();
        verify(subscriber, never()).close();
        controls.release();
        controls.close();
        verify(subscriber, times(1)).close();
    }
}
