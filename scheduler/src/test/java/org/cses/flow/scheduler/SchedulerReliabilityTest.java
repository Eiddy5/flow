package org.cses.flow.scheduler;

import org.cses.flow.scheduler.events.SchedulerEvent;
import org.cses.flow.core.services.executions.WorkflowUcFixture;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.infrastructure.queues.pulsar.PulsarTestEnvironment;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonObject;

import java.sql.DriverManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 真实数据库、Broker 和应用上下文上的调度恢复与重投技术测试。 */
class SchedulerReliabilityTest {
    /** 两个真实上下文共享数据库与订阅，一次计划命中仍只创建一个实例。 */
    @Test
    void twoSchedulersCompeteForOneDurableOccurrence() {
        try (var first = SchedulerTestSupport.open(); var second = SchedulerTestSupport.open()) {
            long due = (TimeUtil.now() / 1000 + 10) * 1000;
            var flow = first.deploy(SchedulerTestSupport.yaml("scheduler-two-nodes", due, due + 120_000));
            SchedulerTestSupport.awaitScheduleCount(flow, 2);
            var executions = SchedulerTestSupport.awaitCount(first, 1, due + 20_000);
            SchedulerTestSupport.assertCompleted(first, flow, executions);
            SchedulerTestSupport.assertCountThrough(first, 1, TimeUtil.now() + 1_000);
            var fromSecond = second.executionService().executions(first.session());
            assertEquals(1, fromSecond.size());
            assertEquals(executions.getFirst().id(), fromSecond.getFirst().id());
            assertTrue(fromSecond.getFirst().activeTaskRuns().isEmpty());
            SchedulerTestSupport.deleteAndAwaitCancellation(first, flow);
        }
    }

    /** 关闭并重新创建应用上下文后，已发布的定时配置仍自动启动同一个 Flow。 */
    @Test
    void persistedScheduleSurvivesApplicationContextRestart() {
        try (var fixture = SchedulerTestSupport.open()) {
            long due = (TimeUtil.now() / 1000 + 15) * 1000;
            var published = fixture.deploy(SchedulerTestSupport.yaml("scheduler-restart", due, due + 120_000));
            String flowKey = published.key();
            long version = published.version();
            fixture.restartServer();
            var flow = fixture.flowService().flow(fixture.session(), flowKey, version).orElseThrow();
            assertEquals(2, flow.triggers().size());
            assertTrue(TimeUtil.now() < due, "重启应在计划时间前完成");
            SchedulerTestSupport.assertCountThrough(fixture, 0, due - 100);
            var executions = SchedulerTestSupport.awaitCount(fixture, 1, due + 20_000);
            SchedulerTestSupport.assertCompleted(fixture, flow, executions);
            SchedulerTestSupport.deleteAndAwaitCancellation(fixture, flow);
        }
    }

    /**
     * 重新投递同一持久化命中事件，真实消费者 ACK 后仍只有原实例。
     * @throws Exception 测试数据库或 Broker 查询失败时抛出
     */
    @Test
    void duplicateTriggerEventKeepsOriginalExecutionIdentity() throws Exception {
        try (var fixture = SchedulerTestSupport.open()) {
            long due = (TimeUtil.now() / 1000 + 10) * 1000;
            var flow = fixture.deploy(SchedulerTestSupport.yaml("scheduler-redelivery", due, due + 120_000));
            var executions = SchedulerTestSupport.awaitCount(fixture, 1, due + 20_000);
            SchedulerTestSupport.assertCompleted(fixture, flow, executions);
            SchedulerEvent event = persistedEvent(fixture);
            assertEquals(executions.getFirst().id(), event.executionId());
            fixture.emitEvent(SchedulerEvent.class, event);
            fixture.emitEvent(SchedulerEvent.class, event);
            awaitAcknowledged();
            var reloaded = fixture.executionService().executions(fixture.session());
            assertEquals(1, reloaded.size());
            assertEquals(executions.getFirst().id(), reloaded.getFirst().id());
            SchedulerTestSupport.assertCompleted(fixture, flow, reloaded);
            SchedulerTestSupport.deleteAndAwaitCancellation(fixture, flow);
        }
    }

    /** 新版本替换 Cron 和输入，禁用与删除均取消未来触发，不启动旧计划。 */
    @Test
    void configurationEventsReplaceDisableAndDeleteSchedules() {
        try (var fixture = SchedulerTestSupport.open()) {
            long oldDue = (TimeUtil.now() / 1000 + 10) * 1000;
            var original = fixture.deploy(withInputs(SchedulerTestSupport.yaml("scheduler-update", oldDue, oldDue + 120_000), "old"));
            SchedulerTestSupport.awaitScheduleCount(original, 2);
            long newDue = oldDue + 10_000;
            var draft = fixture.flowService().save(fixture.session(), PublishFlowCommand.from(original.key(),
                withInputs(SchedulerTestSupport.yaml(original.key(), newDue, newDue + 120_000), "new")));
            var revisedDraft = fixture.flowService().save(fixture.session(), PublishFlowCommand.from(original.key(),
                withInputs(SchedulerTestSupport.yaml(original.key(), newDue, newDue + 120_000), "new")));
            assertEquals(draft.triggers().stream().map(trigger -> trigger.id()).toList(),
                revisedDraft.triggers().stream().map(trigger -> trigger.id()).toList());
            var changed = fixture.flowService().save(fixture.session(), PublishFlowCommand.from(revisedDraft.key(), false));
            assertEquals(original.triggers().getFirst().id(), changed.triggers().getFirst().id());
            SchedulerTestSupport.assertCountThrough(fixture, 0, oldDue + 1_000);
            var executions = SchedulerTestSupport.awaitCount(fixture, 1, newDue + 20_000);
            assertEquals("new", executions.getFirst().inputs().get("source"));
            SchedulerTestSupport.assertCompleted(fixture, changed, executions);

            long disabledDue = (TimeUtil.now() / 1000 + 6) * 1000;
            String disabledYaml = SchedulerTestSupport.yaml(original.key(), disabledDue, disabledDue)
                .replace("timezone: Asia/Shanghai", "timezone: Asia/Shanghai\n    disabled: true");
            fixture.flowService().save(fixture.session(), PublishFlowCommand.from(original.key(), disabledYaml));
            var disabled = fixture.flowService().save(fixture.session(), PublishFlowCommand.from(original.key(), false));
            SchedulerTestSupport.awaitScheduleCount(disabled, 0);
            SchedulerTestSupport.assertCountThrough(fixture, 1, disabledDue + 1_000);

            long deletedDue = (TimeUtil.now() / 1000 + 8) * 1000;
            fixture.flowService().save(fixture.session(), PublishFlowCommand.from(original.key(),
                SchedulerTestSupport.yaml(original.key(), deletedDue, deletedDue)));
            var enabled = fixture.flowService().save(fixture.session(), PublishFlowCommand.from(original.key(), false));
            SchedulerTestSupport.awaitScheduleCount(enabled, 2);
            SchedulerTestSupport.deleteAndAwaitCancellation(fixture, enabled);
            SchedulerTestSupport.assertCountThrough(fixture, 1, deletedDue + 1_000);
            SchedulerTestSupport.assertCompleted(fixture, changed, fixture.executionService().executions(fixture.session()));
        }
    }

    /**
     * 为测试 Flow 声明输入并给两个触发器配置相同的常量值。
     * @param yaml 原始双触发器 YAML
     * @param value 要验证的输入快照值
     * @return 新定义文本
     */
    private static String withInputs(String yaml, String value) {
        return yaml.replace("triggers:", "inputs:\n  - key: source\n    type: STRING\ntriggers:")
            .replace("timezone: Asia/Shanghai", "timezone: Asia/Shanghai\n    inputs:\n      source: " + value);
    }

    /**
     * 只读取得本租户已持久化的原始命中事件，供技术重投验证使用。
     * @param fixture 本次测试租户的真实服务夹具
     * @return 数据库中唯一的原始命中事件
     * @throws Exception 数据库读取或反序列化失败时抛出
     */
    private static SchedulerEvent persistedEvent(WorkflowUcFixture fixture) throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("FLOW_POSTGRES_TEST_URL"),
            System.getenv("FLOW_POSTGRES_TEST_USER"), System.getenv("FLOW_POSTGRES_TEST_PASSWORD"));
             var query = connection.prepareStatement(
                 "select payload::text from scheduler_events where company_id = ? and kind = 'TRIGGER_FIRED'")) {
            query.setString(1, fixture.session().getCompanyId());
            try (var result = query.executeQuery()) {
                assertTrue(result.next(), "应保留可靠投递的命中事件");
                SchedulerEvent event = JsonObject.Parse(result.getString(1)).asObject(SchedulerEvent.class);
                assertFalse(result.next(), "同次命中只应有一条事件");
                return event;
            }
        }
    }

    /**
     * 等待真实 Broker 确认调度事件订阅无积压且无未确认消息。
     * @throws Exception Broker 查询失败、中断或超过等待上限时抛出
     */
    private static void awaitAcknowledged() throws Exception {
        long deadline = TimeUtil.now() + 10_000;
        try (var admin = PulsarTestEnvironment.admin()) {
            do {
                var stats = admin.topics().getStats("persistent://public/default/" + SchedulerEvent.QUEUE_NAME);
                var subscription = stats.getSubscriptions().get(SchedulerEvent.QUEUE_NAME);
                if (subscription != null && subscription.getMsgBacklog() == 0 && subscription.getUnackedMessages() == 0) return;
                new CountDownLatch(1).await(25, TimeUnit.MILLISECONDS);
            } while (TimeUtil.now() < deadline);
        }
        fail("调度事件重投后 Broker 未完成 ACK");
    }
}
