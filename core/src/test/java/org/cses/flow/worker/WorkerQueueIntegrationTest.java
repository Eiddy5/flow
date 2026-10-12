package org.cses.flow.worker;

import org.apache.pulsar.client.admin.PulsarAdmin;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.services.executions.RetryBusinessTask;
import org.cses.flow.core.services.executions.SubmittedBusiness;
import org.cses.flow.core.services.executions.WorkflowUcFixture;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.infrastructure.queues.pulsar.PulsarTestEnvironment;
import org.flow.gen.flow.Tables;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonObject;
import org.x9.jooq.JOOQ;

import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 PG 与 Pulsar 双向队列、ACK 边界和消费后清理的独立技术验证，不作为 UC 断言。 */
class WorkerQueueIntegrationTest {
    private static final String WORKER_ENABLED = "flow.worker.enabled";
    private static final String RESULT_ENABLED = "flow.executor.worker-result.enabled";
    private static final String TOPIC_PREFIX = "persistent://public/default/";
    private static final String WORKER_KIND = "WORKER";
    private static final String RESULT_KIND = "RESULT";

    /**
     * 核对提交等待、执行期间未 ACK、双向结果接纳及 outbox 删除后的重复消息去重。
     * @throws Exception 本机业务样本或真实 Broker 无法访问时抛出
     */
    @Test
    void completedMessagesAreDeletedAndDuplicatesRemainHarmless() throws Exception {
        try (SubmittedBusiness business = new SubmittedBusiness();
             WorkflowUcFixture fixture = open(Map.of(WORKER_ENABLED, false));
             PulsarAdmin admin = PulsarTestEnvironment.admin()) {
            String id = start(fixture, business);
            Execution submitted = fixture.awaitExecution(fixture.session(), id,
                value -> value.taskRuns().stream().anyMatch(run -> run.state().is(State.Type.SUBMITTED)));
            String taskRunId = submitted.taskRuns().getFirst().id();
            WorkerCommand command = command(fixture, id);
            assertEquals(taskRunId, command.taskRunId());
            assertEquals(1, command.attemptNumber());
            assertTrue(submitted.taskRuns().getFirst().attempts().isEmpty());
            assertTrue(business.calls("a").isEmpty());
            assertEquals(1, count(fixture, id, WORKER_KIND));

            fixture.restartServerWithProperties(Map.of(WORKER_ENABLED, true));
            fixture.awaitExecution(fixture.session(), id, value -> value.requireTaskRun(taskRunId).state().is(State.Type.RUNNING));
            await(() -> !business.calls("a").isEmpty());
            assertTrue(admin.topics().getStats(TOPIC_PREFIX + WorkerCommand.QUEUE_NAME)
                .getSubscriptions().get(WorkerCommand.QUEUE_NAME).getUnackedMessages() >= 1,
                "Runner 尚未完成时原任务命令不能 ACK");
            assertEquals(1, count(fixture, id, WORKER_KIND));
            assertTrue(business.calls("b").isEmpty());
            long activeCommandsBefore = deliveries(admin, WorkerCommand.QUEUE_NAME);
            fixture.emitEvent(WorkerCommand.class, command);
            fixture.emitEvent(WorkerCommand.class, command);
            await(() -> deliveries(admin, WorkerCommand.QUEUE_NAME) >= activeCommandsBefore + 2);
            assertEquals(1, business.calls("a").size(), "活跃许可期间重复命令不能再次执行 Runner");
            business.release();
            Execution completed = fixture.awaitExecution(fixture.session(), id, Execution::isTerminal);
            assertEquals(State.Type.SUCCESS, completed.state().current());
            await(() -> count(fixture, id, null) == 0);
            awaitDrained(admin);
            WorkerResultEvent result = result(fixture, id, taskRunId);
            long commandsBefore = deliveries(admin, WorkerCommand.QUEUE_NAME);
            long resultsBefore = deliveries(admin, WorkerResultEvent.QUEUE_NAME);
            fixture.emitEvent(WorkerCommand.class, command);
            fixture.emitEvent(WorkerCommand.class, command);
            fixture.emitEvent(WorkerResultEvent.class, result);
            fixture.emitEvent(WorkerResultEvent.class, result);
            await(() -> deliveries(admin, WorkerCommand.QUEUE_NAME) >= commandsBefore + 2
                && deliveries(admin, WorkerResultEvent.QUEUE_NAME) >= resultsBefore + 2);
            awaitDrained(admin);
            assertEquals(0, count(fixture, id, null), "已删除消息不得被重新插入 outbox");
            assertEquals(1, business.calls("a").size());
            assertEquals(List.of("seed-b"), business.calls("b").stream().map(SubmittedBusiness.Call::body).toList());
            Execution queried = query(fixture, id);
            assertEquals(State.Type.SUCCESS, queried.state().current());
            assertEquals(1, queried.requireTaskRun(taskRunId).attempts().size());
            assertEquals(completed.taskRuns().size(), queried.taskRuns().size());
            assertTrue(queried.activeTaskRuns().isEmpty());
        }
    }

    /**
     * 结果接纳暂停时保留正文；应用重建后接纳原结果而不再次调用业务，并清理消息。
     * @throws Exception 外部样本、Broker 或数据库无法访问时抛出
     */
    @Test
    void savedResultSurvivesRestartAndIsConsumedWithoutAnotherRunnerCall() throws Exception {
        try (SubmittedBusiness business = new SubmittedBusiness();
             WorkflowUcFixture fixture = open(Map.of(RESULT_ENABLED, false));
             PulsarAdmin admin = PulsarTestEnvironment.admin()) {
            business.release();
            String id = start(fixture, business);
            await(() -> count(fixture, id, RESULT_KIND) == 1);
            Execution running = query(fixture, id);
            String taskRunId = running.taskRuns().getFirst().id();
            assertEquals(State.Type.RUNNING, running.requireTaskRun(taskRunId).state().current());
            assertEquals(1, running.requireTaskRun(taskRunId).attempts().size());
            assertNull(running.requireTaskRun(taskRunId).attempts().getFirst().completedAt());
            assertEquals(1, business.calls("a").size());
            assertTrue(business.calls("b").isEmpty());
            var saved = database(fixture).get(dsl -> dsl.selectFrom(Tables.EXECUTION_MESSAGES)
                .where(Tables.EXECUTION_MESSAGES.EXECUTION_ID.eq(id)).and(Tables.EXECUTION_MESSAGES.KIND.eq(RESULT_KIND))
                .fetchSingle());
            WorkerResultEvent original = WorkerResultEvent.from(saved.getId(), saved.getCompanyId(), id,
                saved.getTaskRunId(), saved.getAttemptNumber(), saved.getDeliveryGeneration());
            fixture.restartServerWithProperties(Map.of(RESULT_ENABLED, true));
            Execution done = fixture.awaitExecution(fixture.session(), id, Execution::isTerminal);
            assertEquals(State.Type.SUCCESS, done.state().current());
            assertEquals(1, business.calls("a").size());
            assertEquals(1, business.calls("b").size());
            assertEquals(1, done.requireTaskRun(taskRunId).attempts().size());
            assertEquals(running.requireTaskRun(taskRunId).attempts().getFirst().startedAt(),
                done.requireTaskRun(taskRunId).attempts().getFirst().startedAt());
            await(() -> count(fixture, id, null) == 0);
            long before = deliveries(admin, WorkerResultEvent.QUEUE_NAME);
            fixture.emitEvent(WorkerResultEvent.class, original);
            await(() -> deliveries(admin, WorkerResultEvent.QUEUE_NAME) > before);
            awaitDrained(admin);
            assertEquals(0, count(fixture, id, null));
            assertEquals(1, business.calls("a").size());
            assertEquals(1, business.calls("b").size());
        }
    }

    /**
     * 装配独立的真实运行环境，并合并控制消费的配置。
     * @param extra 只包含本场景消费开关的配置
     * @return 调用方负责关闭的真实服务夹具
     */
    private WorkflowUcFixture open(Map<String, Object> extra) {
        Map<String, Object> properties = new java.util.LinkedHashMap<>(extra);
        properties.put("flow.test.retry-task", true);
        return WorkflowUcFixture.openWithProperties(properties);
    }

    /**
     * 通过公开服务发布并启动 A 成功后由 B 消费结果的独立流程。
     * @param fixture 本场景真实服务与会话
     * @param business 本场景独立外部样本
     * @return 公开启动取得的原运行编号
     */
    private String start(WorkflowUcFixture fixture, SubmittedBusiness business) {
        var flow = fixture.deploy("""
            key: worker-queue-technical
            tasks:
              - key: a
                type: %s
                endpoint: '%s'
                message: seed
              - key: b
                type: %s
                endpoint: '%s'
                message: '{{ outputs.a.value }}-b'
            """.formatted(RetryBusinessTask.class.getName(), business.endpoint("a"),
                RetryBusinessTask.class.getName(), business.endpoint("b")));
        return fixture.startCreated(flow).id();
    }

    /**
     * 查询当前真实数据库组件；只用于独立技术断言，不修改业务状态。
     * @param fixture 当前应用夹具
     * @return 应用管理的具名 Flow 数据库
     */
    private JOOQ database(WorkflowUcFixture fixture) { return fixture.bean(JOOQ.class, "flow"); }

    /**
     * 读取公开运行快照。
     * @param fixture 当前公开服务
     * @param id 原运行编号
     * @return 租户范围内最新运行
     */
    private Execution query(WorkflowUcFixture fixture, String id) {
        return fixture.executionService().execution(fixture.session(), id).orElseThrow();
    }

    /**
     * 查询消费前的原命令正文。
     * @param fixture 当前真实数据库夹具
     * @param id 原运行编号
     * @return 保存时分配的同一命令身份
     */
    private WorkerCommand command(WorkflowUcFixture fixture, String id) {
        String content = database(fixture).get(dsl -> dsl.select(Tables.EXECUTION_MESSAGES.PAYLOAD)
            .from(Tables.EXECUTION_MESSAGES).where(Tables.EXECUTION_MESSAGES.EXECUTION_ID.eq(id))
            .and(Tables.EXECUTION_MESSAGES.KIND.eq(WORKER_KIND)).fetchSingle().value1().data());
        return JsonObject.Parse(content).asObject(WorkerCommand.class);
    }

    /**
     * 读取消费后有限许可中的接纳身份，用于重投已清理消息。
     * @param fixture 当前真实数据库夹具
     * @param id 原运行编号
     * @param taskRunId 已完成原步骤编号
     * @return 原结果的稳定引用
     */
    private WorkerResultEvent result(WorkflowUcFixture fixture, String id, String taskRunId) {
        var row = database(fixture).get(dsl -> dsl.selectFrom(Tables.WORKER_DELIVERIES)
            .where(Tables.WORKER_DELIVERIES.EXECUTION_ID.eq(id)).and(Tables.WORKER_DELIVERIES.TASK_RUN_ID.eq(taskRunId))
            .fetchSingle());
        assertEquals("SETTLED", row.getPhase());
        return WorkerResultEvent.from(row.getResultId(), row.getCompanyId(), id, taskRunId,
            row.getAttemptNumber(), row.getDeliveryGeneration());
    }

    /**
     * 查询当前运行仍存在的消息数量。
     * @param fixture 当前真实数据库夹具
     * @param id 原运行编号
     * @param kind 消息类型，null 表示全部类型
     * @return 本次运行未清理的消息数量
     */
    private int count(WorkflowUcFixture fixture, String id, String kind) {
        return database(fixture).get(dsl -> dsl.fetchCount(Tables.EXECUTION_MESSAGES,
            Tables.EXECUTION_MESSAGES.EXECUTION_ID.eq(id).and(kind == null
                ? org.jooq.impl.DSL.trueCondition() : Tables.EXECUTION_MESSAGES.KIND.eq(kind))));
    }

    /**
     * 查询真实订阅的累计投递数量。
     * @param admin 本测试拥有的 Broker 管理连接
     * @param queue 两条 Worker 队列之一
     * @return Broker 已投递给订阅的总数量
     * @throws Exception 只读管理查询失败时抛出
     */
    private long deliveries(PulsarAdmin admin, String queue) throws Exception {
        return admin.topics().getStats(TOPIC_PREFIX + queue).getSubscriptions().get(queue).getMsgOutCounter();
    }

    /**
     * 等待两条真实订阅都无积压和未确认消息；不跳过或删除 Broker 消息。
     * @param admin 当前只读管理连接
     * @throws Exception 查询失败或 20 秒内未清空时抛出
     */
    private void awaitDrained(PulsarAdmin admin) throws Exception {
        await(() -> {
            for (String queue : List.of(WorkerCommand.QUEUE_NAME, WorkerResultEvent.QUEUE_NAME)) {
                var stats = admin.topics().getStats(TOPIC_PREFIX + queue).getSubscriptions().get(queue);
                if (stats.getMsgBacklog() != 0 || stats.getUnackedMessages() != 0) return false;
            }
            return true;
        });
    }

    /**
     * 按可观察条件最多等待 20 秒，不用固定时长替代状态检查。
     * @param check 只读观察条件
     * @throws Exception 查询失败或超时断言失败时抛出
     */
    private void await(Check check) throws Exception {
        long end = TimeUtil.now() + 20000;
        while (TimeUtil.now() < end) {
            if (check.get()) return;
            LockSupport.parkNanos(10_000_000);
        }
        fail("真实 Worker 队列条件在 20 秒内未成立");
    }

    private interface Check {
        /**
         * 查询一个真实环境事实。
         * @return 当前条件成立时为 true
         * @throws Exception 查询失败时抛出
         */
        boolean get() throws Exception;
    }
}
