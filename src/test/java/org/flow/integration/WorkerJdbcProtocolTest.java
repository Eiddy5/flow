package org.flow.integration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.flow.core.executor.WorkerJobRunningStateStore;
import org.flow.core.migration.MigrationRunner;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.flows.State;
import org.flow.core.queues.DispatchQueueInterface;
import org.flow.core.queues.KeyedDispatchQueueInterface;
import org.flow.core.runners.*;
import org.flow.core.utils.IdUtils;
import org.flow.jdbc.runner.AbstractJdbcWorkerJobRunningStateStore;
import org.flow.plugin.core.log.Log;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 PG 协议验收：领取与归属原子提交，结果与完成标记原子提交。 */
@MicronautTest(startApplication = false, transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkerJdbcProtocolTest implements TestPropertyProvider {
    @Inject ApplicationContext context;
    @Inject KeyedDispatchQueueInterface<WorkerJobEvent> queue;
    @Inject DispatchQueueInterface<WorkerTaskResult> results;
    @Inject WorkerJobRunningStateStore jobs;

    @Override
    public Map<String, String> getProperties() {
        var properties = new HashMap<>(new FlowJdbcTest().getProperties());
        properties.put("datasources.flow.schema", "flow_worker_protocol");
        properties.put("flow.jdbc.queues.poll-size", "10");
        return properties;
    }

    @BeforeAll
    void migrate() throws Exception {
        context.getBean(MigrationRunner.class).runAlways();
    }

    @Test
    void claimRollsBackAndDuplicateCannotStealOwnership() throws Exception {
        WorkerTask task = task();
        String route = IdUtils.create();
        queue.emit(route, WorkerJobEvent.of(task, route));
        assertThrows(IllegalStateException.class, () -> queue.claim(route, 1, (tx, event) -> {
            assertTrue(jobs.claim(tx, WorkerTaskRunning.of(task, new WorkerInstance("first", route))));
            throw new IllegalStateException("force rollback");
        }));
        assertEquals(1, queue.queueLag(route));
        assertFalse(stored(task.uid()));
        assertEquals(1, queue.claim(route, 1, (tx, event) ->
            jobs.claim(tx, WorkerTaskRunning.of(task, new WorkerInstance("first", route)))).size());
        assertEquals(0, queue.queueLag(route));
        assertTrue(stored(task.uid()));
        queue.emit(route, WorkerJobEvent.of(task, route));
        assertTrue(queue.claim(route, 1, (tx, event) ->
            jobs.claim(tx, WorkerTaskRunning.of(task, new WorkerInstance("second", route)))).isEmpty());
        assertFalse(jobs.publishResult(task.uid(), "second", null, null, true, "result", () -> fail("wrong owner")));
        jobs.deleteByKey(task.uid());
    }

    @Test
    void resultAndCompletionFenceCommitTogether() throws Exception {
        WorkerTask task = task();
        String route = IdUtils.create();
        queue.emit(route, WorkerJobEvent.of(task, route));
        queue.claim(route, 1, (tx, event) -> jobs.claim(tx, WorkerTaskRunning.of(task, new WorkerInstance("owner", route))));
        var completed = new State(State.Type.SUCCESS, List.of(
            new State.History(State.Type.SUCCESS, java.time.Instant.parse("2026-10-13T08:00:00.123456789Z"))));
        WorkerTaskResult result = new WorkerTaskResult(task.getTaskRun().toBuilder().state(completed).build());
        var mapper = org.flow.core.serializers.JacksonMapper.ofJson();
        var delivered = mapper.readValue(mapper.writeValueAsBytes(result), WorkerTaskResult.class);
        assertNotEquals(result.getTaskRun().getState().getEndDate(), delivered.getTaskRun().getState().getEndDate(),
            "用纳秒时间验证队列序列化后的精度变化");
        assertEquals(result.completionId(), delivered.completionId(), "完成标记必须在队列往返后保持一致");
        int before = context.getBean(org.flow.queue.jdbc.client.JdbcQueueClient.class).queueLag(results.queueName(), null);
        assertThrows(IllegalStateException.class, () -> jobs.publishResult(task.uid(), "owner", null, null, true, result.completionId(), () -> {
            emit(result);
            throw new IllegalStateException("rollback after enqueue");
        }));
        assertEquals(before, context.getBean(org.flow.queue.jdbc.client.JdbcQueueClient.class).queueLag(results.queueName(), null), "回滚不能留下可见结果");
        assertTrue(jobs.publishResult(task.uid(), "owner", null, null, true, result.completionId(), () -> emit(result)));
        assertEquals(before + 1, context.getBean(org.flow.queue.jdbc.client.JdbcQueueClient.class).queueLag(results.queueName(), null));
        assertTrue(entries().stream().filter(job -> job.uid().equals(task.uid())).findFirst().orElseThrow().isResultPending());
        assertFalse(jobs.publishResult(task.uid(), "owner", null, null, true, result.completionId(), () -> fail("duplicate result")));
        jobs.acknowledgeResult(task.uid(), "older-result");
        assertTrue(stored(task.uid()));
        jobs.acknowledgeResult(task.uid(), delivered.completionId());
        assertFalse(stored(task.uid()));
    }

    @Test
    void competingWorkersSkipLockedAndRespectCapacity() throws Exception {
        String route = IdUtils.create();
        WorkerTask first = task();
        WorkerTask second = task();
        queue.emit(route, List.of(WorkerJobEvent.of(first, route), WorkerJobEvent.of(second, route)));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> queue.claim(route, 1, (tx, event) -> {
                locked.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return jobs.claim(tx, WorkerTaskRunning.of((WorkerTask) event.getLeft().job(), new WorkerInstance("a", route)));
            }));
            try {
                assertTrue(locked.await(10, TimeUnit.SECONDS));
                var b = pool.submit(() -> queue.claim(route, 1, (tx, event) ->
                    jobs.claim(tx, WorkerTaskRunning.of((WorkerTask) event.getLeft().job(), new WorkerInstance("b", route)))))
                    .get(5, TimeUnit.SECONDS);
                assertEquals(1, b.size());
                assertEquals(second.uid(), b.getFirst().job().uid());
            } finally {
                release.countDown();
            }
            assertEquals(first.uid(), a.get(5, TimeUnit.SECONDS).getFirst().job().uid());
        }
        assertEquals(0, queue.queueLag(route));
        jobs.deleteByKey(first.uid());
        jobs.deleteByKey(second.uid());
    }

    @Test
    void newerTriggerGenerationWaitsForPreviousOwner() throws Exception {
        String route = IdUtils.create();
        var data = new WorkerTriggerData("main", "migration.acceptance", IdUtils.create(),
            java.time.ZonedDateTime.now(), 1, null, null, Map.of(), null, Map.of());
        var plugin = org.flow.plugin.core.trigger.Schedule.builder().id("clock")
            .type(org.flow.plugin.core.trigger.Schedule.class.getName()).cron("* * * * *").build();
        var old = WorkerTrigger.builder().trigger(plugin).data(data).dispatchEpoch(1).build();
        var next = WorkerTrigger.builder().trigger(plugin).data(data).dispatchEpoch(2).build();
        queue.emit(route, WorkerJobEvent.of(old, route));
        queue.claim(route, 1, (tx, event) -> jobs.claim(tx, WorkerTriggerRunning.of(old, new WorkerInstance("owner", route))));
        queue.emit(route, WorkerJobEvent.of(next, route));
        assertThrows(IllegalStateException.class, () -> queue.claim(route, 1, (tx, event) ->
            jobs.claim(tx, WorkerTriggerRunning.of(next, new WorkerInstance("owner", route)))));
        assertEquals(1, queue.queueLag(route), "新一代调度必须保留在队列中");
        assertTrue(jobs.publishResult(old.uid(), "owner", null, 1L, true, null, () -> {}));
        assertEquals(1, queue.claim(route, 1, (tx, event) ->
            jobs.claim(tx, WorkerTriggerRunning.of(next, new WorkerInstance("owner", route)))).size());
        assertFalse(jobs.publishResult(old.uid(), "owner", null, 1L, true, null, () -> fail("stale trigger result")));
        assertTrue(jobs.publishResult(next.uid(), "owner", null, 2L, true, null, () -> {}));
    }

    @Test
    void oldDeliveryCannotPublishAfterSameWorkerClaimsRetry() throws Exception {
        WorkerTask task = task();
        String route = IdUtils.create();
        var first = WorkerTaskRunning.of(task, new WorkerInstance("same-worker", route));
        first.setDeliveryId("delivery-one");
        queue.emit(route, WorkerJobEvent.of(task, route));
        queue.claim(route, 1, (tx, event) -> jobs.claim(tx, first));
        assertTrue(jobs.publishResult(task.uid(), "same-worker", "delivery-one", null, true, "first-result", () -> {}));
        jobs.acknowledgeResult(task.uid(), "first-result");

        var retry = WorkerTaskRunning.of(task, new WorkerInstance("same-worker", route));
        retry.setDeliveryId("delivery-two");
        queue.emit(route, WorkerJobEvent.of(task, route));
        queue.claim(route, 1, (tx, event) -> jobs.claim(tx, retry));
        // The first commit succeeded but its response was lost; retrying that old
        // publication must not mark the new delivery complete or release its lease.
        assertFalse(jobs.publishResult(task.uid(), "same-worker", "delivery-one", null, true, "first-result",
            () -> fail("Old delivery must not publish against the new lease")));
        jobs.acknowledgeResult(task.uid(), "first-result");
        assertTrue(stored(task.uid()));
        assertTrue(jobs.publishResult(task.uid(), "same-worker", "delivery-two", null, true, "second-result", () -> {}));
        jobs.acknowledgeResult(task.uid(), "second-result");
        assertFalse(stored(task.uid()));
    }

    private void emit(WorkerTaskResult result) {
        try {
            results.emit(result);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<WorkerJobRunning> entries() {
        return ((AbstractJdbcWorkerJobRunningStateStore) jobs).findAll();
    }

    private boolean stored(String id) {
        return entries().stream().anyMatch(job -> job.uid().equals(id));
    }

    private static WorkerTask task() {
        return WorkerTask.builder()
            .taskRun(TaskRun.builder().id(IdUtils.create()).executionId(IdUtils.create())
                .tenantId("main").namespace("migration.acceptance").flowId("jdbc_protocol")
                .taskId("log").state(new State().withState(State.Type.SUBMITTED)).build())
            .task(Log.builder().id("log").type(Log.class.getName()).message("test").build())
            .data(new WorkerTaskData(Map.of(), null)).build();
    }
}
