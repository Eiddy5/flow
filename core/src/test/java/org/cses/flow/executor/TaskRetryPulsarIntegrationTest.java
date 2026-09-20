package org.cses.flow.executor;

import com.sun.net.httpserver.HttpServer;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.core.services.executions.RetryBusinessTask;
import org.cses.flow.core.services.executions.WorkflowUcFixture;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.infrastructure.jooq.PostgresJooqTestAdapter;
import org.cses.flow.infrastructure.queues.pulsar.PulsarTestEnvironment;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;
import static org.junit.jupiter.api.Assertions.*;

/** 专用重试队列的真实 Broker、生产消费者和 PostgreSQL 链路技术验证。 */
class TaskRetryPulsarIntegrationTest {
    /** 提前送达通过生产回调NACK重投；普通UPDATED不唤醒，成功后的旧消息被ACK且不再调用。
     * @throws Exception 外部业务服务或Broker查询失败
     */
    @Test
    void earlyDeliveryIsRejectedThenRedeliveredByBroker() throws Exception {
        try (Business business = new Business(); WorkflowUcFixture fixture = open(false); PulsarAdmin admin = PulsarTestEnvironment.admin()) {
            TaskRetryEvent event = start(fixture, business);
            long before = deliveries(admin);
            fixture.emitEvent(ExecutorEvent.class, ExecutorEvent.from(event.executionId(), event.companyId(), ExecutorEvent.EventType.UPDATED));
            fixture.emitEvent(TaskRetryEvent.class, event);
            await(() -> deliveries(admin) > before, 4000);
            assertTrue(TimeUtil.now() < event.deliverAt());
            assertEquals(State.Type.RETRY_WAIT, query(fixture, event).taskRuns().getFirst().state().current());
            assertEquals(1, business.calls.size());
            assertSuccess(fixture, business, event);
            assertTrue(deliveries(admin) >= before + 2, "同一提前消息必须经Broker再次投递");
            fixture.emitEvent(TaskRetryEvent.class, event);
            awaitAcknowledged(admin);
            assertEquals(2, business.calls.size());
            assertEquals(State.Type.SUCCESS, query(fixture, event).state().current());
        }
    }

    /** 两条同身份的真实延迟消息在服务重启后最多触发一次再次业务调用。
     * @throws Exception 业务服务或Broker查询失败
     */
    @Test
    void duplicateDelayedMessagesSurviveRestartWithoutDuplicateCalls() throws Exception {
        try (Business business = new Business(); WorkflowUcFixture fixture = open(false); PulsarAdmin admin = PulsarTestEnvironment.admin()) {
            TaskRetryEvent event = start(fixture, business);
            fixture.emitEventAt(TaskRetryEvent.class, event, event.deliverAt());
            fixture.emitEventAt(TaskRetryEvent.class, event, event.deliverAt());
            assertEquals(1, business.calls.size());
            fixture.restartServer();
            assertSuccess(fixture, business, event);
            awaitAcknowledged(admin);
            assertEquals(2, business.calls.size());
        }
    }

    /** Outbox在到期前交给Broker，确认后不再待发；关闭服务后仍由已持久化延迟消息恢复。
     * @throws Exception 业务服务或Broker查询失败
     */
    @Test
    void acknowledgedFutureMessageRecoversAfterApplicationRestart() throws Exception {
        try (Business business = new Business(); WorkflowUcFixture fixture = open(true); PulsarAdmin admin = PulsarTestEnvironment.admin()) {
            TaskRetryEvent event = start(fixture, business);
            fixture.bean(TaskRetryOutboxPublisher.class).poll();
            var repository = fixture.bean(ExecutionRepository.class);
            var database = PostgresJooqTestAdapter.fromEnvironment();
            assertTrue(database.get(dsl -> repository.pendingRetries(dsl, 100)).stream()
                .noneMatch(p -> p.executionId().equals(event.executionId())));
            assertTrue(TimeUtil.now() < event.deliverAt());
            assertEquals(State.Type.RETRY_WAIT, query(fixture, event).taskRuns().getFirst().state().current());
            fixture.restartServer();
            assertSuccess(fixture, business, event);
            awaitAcknowledged(admin);
        }
    }

    /**
     * @param publish true启用真实outbox发布，false仅由测试投递消息
     * @return 本场景独占真实装配夹具
     */
    private WorkflowUcFixture open(boolean publish) {
        return WorkflowUcFixture.openWithProperties(Map.of("flow.test.retry-task", true,
            "flow.executor.retry.outbox.enabled", publish, "flow.executor.retry.outbox.poll-interval", "1h"));
    }

    /**
     * @param fixture 公开入口夹具
     * @param business 本场景独立业务系统
     * @return 从公开运行查询得到的原重试身份
     */
    private TaskRetryEvent start(WorkflowUcFixture fixture, Business business) {
        var flow = fixture.deploy("""
            key: retry-pulsar
            tasks:
              - key: business
                type: %s
                endpoint: 'http://127.0.0.1:%d/'
                message: ready
                retry: {type: constant, maxAttempts: 2, intervalMillis: 8000}
            """.formatted(RetryBusinessTask.class.getName(), business.server.getAddress().getPort()));
        String id = fixture.startCreated(flow).id();
        Execution waiting = fixture.awaitExecution(fixture.session(), id,
            e -> e.taskRuns().stream().anyMatch(t -> t.state().is(State.Type.RETRY_WAIT)));
        var run = waiting.taskRuns().getFirst();
        return TaskRetryEvent.from(waiting.companyId(), waiting.id(), run.id(), 2, run.nextRetryAt());
    }

    /**
     * @param fixture 公开查询入口
     * @param event 稳定运行身份
     * @return 最新公开运行快照
     */
    private Execution query(WorkflowUcFixture fixture, TaskRetryEvent event) {
        return fixture.executionService().execution(fixture.session(), event.executionId()).orElseThrow();
    }

    /**
     * @param fixture 公开查询入口
     * @param business 真实业务调用记录
     * @param event 原重试计划
     * @throws Exception 查询或等待终态失败
     */
    private void assertSuccess(WorkflowUcFixture fixture, Business business, TaskRetryEvent event) throws Exception {
        await(() -> query(fixture, event).isTerminal(), 30000);
        Execution done = query(fixture, event);
        assertEquals(State.Type.SUCCESS, done.state().current());
        var run = done.taskRuns().getFirst();
        assertEquals(event.taskRunId(), run.id());
        assertEquals(2, run.attemptNumber());
        assertEquals(2, run.attempts().size());
        assertEquals(State.Type.FAILED, run.attempts().getFirst().state());
        assertEquals(State.Type.SUCCESS, run.attempts().getLast().state());
        assertEquals("ready", run.outputs().get("value"));
        assertEquals(2, business.calls.size());
        assertTrue(business.calls.get(1) >= event.deliverAt());
        assertTrue(done.activeTaskRuns().isEmpty());
        assertNull(run.nextRetryAt());
    }

    /**
     * @param admin 本场景Broker管理连接
     * @return 专用订阅累计投递次数
     * @throws Exception 查询失败
     */
    private long deliveries(PulsarAdmin admin) throws Exception {
        return admin.topics().getStats("persistent://public/default/flow-delay")
            .getSubscriptions().get(TaskRetryEvent.QUEUE_NAME).getMsgOutCounter();
    }

    /**
     * @param admin 本场景Broker管理连接
     * @throws Exception 查询失败或20秒内仍未确认消息
     */
    private void awaitAcknowledged(PulsarAdmin admin) throws Exception {
        await(() -> {
            var stats = admin.topics().getStats("persistent://public/default/flow-delay")
                .getSubscriptions().get(TaskRetryEvent.QUEUE_NAME);
            return stats.getMsgBacklog() == 0 && stats.getUnackedMessages() == 0;
        }, 20000);
    }

    /**
     * @param condition 可观察事实的查询
     * @param timeoutMillis 最大等待毫秒
     * @throws Exception 查询异常或超时断言失败
     */
    private void await(Check condition, long timeoutMillis) throws Exception {
        long deadline = TimeUtil.now() + timeoutMillis;
        while (TimeUtil.now() < deadline) {
            if (condition.get()) return;
            LockSupport.parkNanos(10_000_000);
        }
        fail("Pulsar condition did not become true");
    }

    private interface Check {
        /** @return 当前观察事实是否成立
         * @throws Exception 查询失败
         */
        boolean get() throws Exception;
    }

    /** 仅替代验收外部业务系统；第一次HTTP失败，第二次成功。 */
    private static class Business implements AutoCloseable {
        HttpServer server;
        List<Long> calls = new CopyOnWriteArrayList<>();
        /** 创建场景私有HTTP服务，记录每次真实调用。
         * @throws Exception 本地端口不可用
         */
        Business() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                calls.add(TimeUtil.now());
                exchange.getRequestBody().readAllBytes();
                byte[] body = (calls.size() == 1 ? "first failure" : "ready").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(calls.size() == 1 ? 503 : 200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }
        /** 停止本场景私有HTTP服务。 */
        @Override public void close() { server.stop(0); }
    }
}
