package org.cses.flow.scheduler;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.services.executions.WorkflowUcFixture;
import org.cses.flow.core.utils.TimeUtil;

import java.time.Instant;
import java.time.ZoneId;
import org.cses.flow.infrastructure.jooq.PostgresJooqTestAdapter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** scheduler 模块复用真实服务夹具的配置与公开查询辅助方法。 */
public class SchedulerTestSupport {
    /** 禁止创建无状态辅助类实例。 */
    private SchedulerTestSupport() {}

    /**
     * 启动连接明确指定测试数据库和 Broker 的真实应用上下文。
     * @return 由调用方关闭的独立租户夹具
     */
    public static WorkflowUcFixture open() {
        Map<String, Object> properties = new java.util.LinkedHashMap<>(Map.of(
            "datasources.flow.enabled", true,
            "datasources.flow.url", System.getenv("FLOW_POSTGRES_TEST_URL"),
            "datasources.flow.username", System.getenv("FLOW_POSTGRES_TEST_USER"),
            "datasources.flow.password", System.getenv("FLOW_POSTGRES_TEST_PASSWORD"),
            "datasources.flow.driver-class-name", "org.postgresql.Driver",
            "datasources.flow.maximum-pool-size", 4,
            "flow.scheduler.poll-interval", "100ms"
        ));
        properties.put("datasources.default.enabled", true);
        properties.put("datasources.default.url", System.getenv("FLOW_POSTGRES_DEFAULT_TEST_URL"));
        properties.put("datasources.default.username", System.getenv("FLOW_POSTGRES_TEST_USER"));
        properties.put("datasources.default.password", System.getenv("FLOW_POSTGRES_TEST_PASSWORD"));
        properties.put("datasources.default.driver-class-name", "org.postgresql.Driver");
        properties.put("datasources.default.maximum-pool-size", 2);
        return WorkflowUcFixture.openWithProperties(properties);
    }

    /**
     * 返回指定时间当天时分秒对应的每日 Cron，时区固定为 Asia/Shanghai。
     * @param timestamp UTC 毫秒时间点
     * @return 六段 SPRING53 表达式
     */
    public static String cron(long timestamp) {
        var time = Instant.ofEpochMilli(timestamp).atZone(ZoneId.of("Asia/Shanghai"));
        return "%d %d %d * * *".formatted(time.getSecond(), time.getMinute(), time.getHour());
    }

    /**
     * 创建含两个定时触发器和两个可自动完成步骤的 YAML。
     * @param key 本场景的 Flow 业务名称
     * @param first 第一个计划时间，UTC 毫秒
     * @param second 第二个计划时间，UTC 毫秒
     * @return 可通过公开发布入口保存的完整 YAML
     */
    public static String yaml(String key, long first, long second) {
        return """
            key: %s
            triggers:
              - key: first
                type: org.cses.flow.extensions.triggers.ScheduleTrigger
                cron: "%s"
                timezone: Asia/Shanghai
              - key: second
                type: org.cses.flow.extensions.triggers.ScheduleTrigger
                cron: "%s"
                timezone: Asia/Shanghai
            tasks:
              - key: first-step
                type: org.cses.flow.extensions.log.Log
                message: first
              - key: second-step
                type: org.cses.flow.extensions.log.Log
                message: second
            """.formatted(key, cron(first), cron(second));
    }

    /**
     * 通过公开查询等待本租户产生指定数量的实例；超过数量立即失败。
     * @param fixture 本场景夹具
     * @param count 期望实例总数
     * @param deadline 最迟等待时间，UTC 毫秒
     * @return 公开查询取得的实例快照
     */
    public static List<Execution> awaitCount(WorkflowUcFixture fixture, int count, long deadline) {
        do {
            var executions = fixture.executionService().executions(fixture.session());
            assertTrue(executions.size() <= count, "不应产生额外实例");
            if (executions.size() == count) return executions;
            waitForChange();
        } while (TimeUtil.now() < deadline);
        throw new AssertionError("到期后未产生预期的 " + count + " 个实例");
    }

    /**
     * 在观察区间内不断公开查询，验证实例数量没有提前增加或重复增加。
     * @param fixture 本场景夹具
     * @param count 观察期间应保持的总数量
     * @param end 观察结束时间，UTC 毫秒
     */
    public static void assertCountThrough(WorkflowUcFixture fixture, int count, long end) {
        do {
            assertEquals(count, fixture.executionService().executions(fixture.session()).size());
            waitForChange();
        } while (TimeUtil.now() < end);
    }

    /**
     * 逐一查询实例至完成，核对完整步骤顺序、版本和无活动步骤。
     * @param fixture 本场景夹具
     * @param flow 公开查询取得的正式 Flow
     * @param executions 本次新增的实例快照
     */
    public static void assertCompleted(WorkflowUcFixture fixture, Flow flow, List<Execution> executions) {
        for (Execution execution : executions) {
            var completed = fixture.awaitExecution(fixture.session(), execution.id(), Execution::isTerminal);
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(flow.key(), completed.flowKey());
            assertEquals(flow.version(), completed.flowVersion());
            assertEquals(flow.tasks().stream().map(task -> task.id()).toList(),
                completed.taskRuns().stream().map(TaskRun::taskId).toList());
            assertTrue(completed.taskRuns().stream().allMatch(run -> run.state().is(State.Type.SUCCESS)));
            assertTrue(completed.activeTaskRuns().isEmpty());
        }
        assertTrue(fixture.pausedTaskRuns().isEmpty());
        assertTrue(fixture.executionService().executions(fixture.session()).stream().allMatch(Execution::isTerminal));
    }

    /**
     * 通过公开删除入口停止调度，并等待该 Flow 的持久化计划全部取消。
     * @param fixture 本场景夹具
     * @param flow 需要清理的正式版本
     */
    public static void deleteAndAwaitCancellation(WorkflowUcFixture fixture, Flow flow) {
        fixture.flowService().delete(fixture.session(), flow.key(), false);
        awaitScheduleCount(flow, 0);
    }

    /**
     * 只读检查本租户 Flow 的持久化计划数量；仅用于技术同步与测试资源清理。
     * @param flow 含本场景独有触发器身份的公开定义
     * @param expected 应存在的注册数，0 表示全部取消
     */
    public static void awaitScheduleCount(Flow flow, int expected) {
        long deadline = TimeUtil.now() + 10_000;
        try (var adapter = PostgresJooqTestAdapter.fromEnvironment();
             var connection = adapter.connection();
             var query = connection.prepareStatement("select count(*) from trigger_schedules where company_id = ? and id = any (?)")) {
            query.setString(1, flow.companyId());
            query.setArray(2, connection.createArrayOf("text", flow.triggers().stream().map(trigger -> trigger.id()).toArray()));
            do {
                try (var result = query.executeQuery()) {
                    result.next();
                    if (result.getInt(1) == expected) return;
                }
                waitForChange();
            } while (TimeUtil.now() < deadline);
            throw new AssertionError("调度注册数量未达到 " + expected);
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("无法读取测试调度注册", failure);
        }
    }

    /** 等待下一次轮询，发生中断时保持中断标记并使测试失败。 */
    private static void waitForChange() {
        try {
            new CountDownLatch(1).await(25, TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待定时执行时中断", failure);
        }
    }
}
