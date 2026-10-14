package org.flow.worker.senders;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.flow.core.worker.models.WorkerContext;
import org.flow.worker.queues.InMemoryWorkerQueue;
import org.flow.worker.queues.WorkerQueueRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueueWorkerIOSenderTest {
    @Test
    void retainsFailedEventAndPreservesOrder() throws Exception {
        var context = new WorkerContext("worker", "default", 1);
        var registry = mock(WorkerQueueRegistry.class);
        var queue = new InMemoryWorkerQueue<String>(4);
        when(registry.getOrCreate(context, String.class)).thenReturn(queue);
        var unavailable = new AtomicBoolean(true);
        List<String> published = new ArrayList<>();
        var sender = new QueueWorkerIOSender<>(registry, String.class, (owner, event) -> {
            if (unavailable.get()) {
                throw new IllegalStateException("temporary database outage");
            }
            published.add(event);
        });
        sender.init(context);
        queue.put("first");
        queue.put("second");
        assertThrows(IllegalStateException.class, sender::doOnLoop);
        assertTrue(sender.hasUndeliveredResults());
        unavailable.set(false);
        sender.doOnLoop();
        sender.cleanup();
        assertEquals(List.of("first", "second"), published);
        assertFalse(sender.hasUndeliveredResults());
    }

    @Test
    void failedShutdownRemainsUndelivered() {
        var context = new WorkerContext("worker", "default", 1);
        var registry = mock(WorkerQueueRegistry.class);
        var queue = new InMemoryWorkerQueue<String>(1);
        when(registry.getOrCreate(context, String.class)).thenReturn(queue);
        var sender = new QueueWorkerIOSender<>(registry, String.class, (owner, event) -> {
            throw new IllegalStateException("database offline");
        });
        sender.init(context);
        queue.put("terminal result");
        assertThrows(IllegalStateException.class, sender::cleanup);
        assertTrue(sender.hasUndeliveredResults());
    }
}
