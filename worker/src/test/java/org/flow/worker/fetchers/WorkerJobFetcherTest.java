package org.flow.worker.fetchers;

import java.time.Duration;
import java.util.List;
import org.flow.core.executor.WorkerJobRunningStateStore;
import org.flow.core.queues.KeyedDispatchQueueInterface;
import org.flow.core.runners.*;
import org.flow.core.worker.models.WorkerContext;
import org.flow.worker.queues.InMemoryWorkerQueue;
import org.flow.worker.queues.WorkerQueueRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkerJobFetcherTest {
    @Test
    @SuppressWarnings("unchecked")
    void onlyClaimsFreeCapacityAndIgnoresUnrelatedCompletion() throws Exception {
        var queue = (KeyedDispatchQueueInterface<WorkerJobEvent>) mock(KeyedDispatchQueueInterface.class);
        var registry = mock(WorkerQueueRegistry.class);
        var context = new WorkerContext("worker", "default", 1, 0);
        var local = new InMemoryWorkerQueue<WorkerJob>(1);
        when(registry.getOrCreate(context, WorkerJob.class)).thenReturn(local);
        var job = mock(WorkerTask.class);
        when(job.uid()).thenReturn("job");
        when(queue.claim(isNull(), eq(1), any())).thenReturn(List.of(WorkerJobEvent.of(job, null)));
        var fetcher = new WorkerJobFetcher(queue, mock(WorkerJobRunningStateStore.class), registry,
            mock(org.flow.worker.services.WorkerControlSubscription.class),
            mock(org.flow.worker.services.WorkerJobErrorHandler.class),
            mock(org.flow.core.scheduler.queue.TriggerEventQueue.class));
        fetcher.init(context);
        fetcher.doOnLoop();
        assertSame(job, local.poll(Duration.ZERO));
        fetcher.onJobCompleted("not-owned");
        fetcher.doOnLoop();
        verify(queue, times(1)).claim(isNull(), eq(1), any());
        fetcher.onJobCompleted("job");
        fetcher.doOnLoop();
        verify(queue, times(2)).claim(isNull(), eq(1), any());
    }
    @Test
    @SuppressWarnings("unchecked")
    void accountsForOverlappingAttemptsOfTheSameTaskRun() throws Exception {
        var queue = (KeyedDispatchQueueInterface<WorkerJobEvent>) mock(KeyedDispatchQueueInterface.class);
        var registry = mock(WorkerQueueRegistry.class);
        var context = new WorkerContext("worker", "default", 2, 0);
        var local = new InMemoryWorkerQueue<WorkerJob>(2);
        when(registry.getOrCreate(context, WorkerJob.class)).thenReturn(local);
        var job = mock(WorkerTask.class);
        when(job.uid()).thenReturn("retrying-task");
        when(queue.claim(isNull(), anyInt(), any()))
            .thenReturn(List.of(WorkerJobEvent.of(job, null)), List.of(WorkerJobEvent.of(job, null)), List.of());
        var fetcher = new WorkerJobFetcher(queue, mock(WorkerJobRunningStateStore.class), registry,
            mock(org.flow.worker.services.WorkerControlSubscription.class),
            mock(org.flow.worker.services.WorkerJobErrorHandler.class),
            mock(org.flow.core.scheduler.queue.TriggerEventQueue.class));
        fetcher.init(context);
        fetcher.doOnLoop();
        local.poll(Duration.ZERO);
        fetcher.doOnLoop();
        local.poll(Duration.ZERO);
        fetcher.onJobCompleted("retrying-task");
        fetcher.doOnLoop();
        verify(queue, times(1)).claim(isNull(), eq(2), any());
        verify(queue, times(2)).claim(isNull(), eq(1), any());
    }

}
