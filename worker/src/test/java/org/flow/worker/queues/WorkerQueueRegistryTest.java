package org.flow.worker.queues;

import org.junit.jupiter.api.Test;

import org.flow.core.metrics.MetricRegistry;
import org.flow.core.models.executions.LogEntry;
import org.flow.core.runners.WorkerJob;
import org.flow.core.worker.models.WorkerContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkerQueueRegistryTest {

    @Test
    void shouldSizeQueuesFromThreadsWhenBufferIsSmallerThanThreads() {
        WorkerQueueRegistry registry = new WorkerQueueRegistry(mock(MetricRegistry.class));
        WorkerContext context = new WorkerContext("worker-1", "group-1", 8, 0);

        WorkerQueue<WorkerJob> jobQueue = registry.getOrCreate(context, WorkerJob.class);
        WorkerQueue<LogEntry> logQueue = registry.getOrCreate(context, LogEntry.class);

        assertThat(jobQueue.capacity()).isEqualTo(8);
        assertThat(logQueue.capacity()).isEqualTo(32);
    }
}
