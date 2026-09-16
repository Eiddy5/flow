package org.cses.flow.core.services.executions;

import org.cses.flow.extensions.triggers.ScheduleTrigger;
import org.cses.flow.scheduler.SchedulerTestSupport;
import org.junit.jupiter.api.Test;
import org.cses.flow.core.utils.TimeUtil;

import static org.junit.jupiter.api.Assertions.*;

/** UC: docs/uc/flow/UC-14 用户通过定时条件启动 Flow.md */
class Uc14ScheduleTriggerTest {
    /** S1：公开保存并查询两个条件，第一个到期独立启动，查询完整结果和无活动步骤。 */
    @Test
    void s1OneDueConditionStartsWithoutWaitingForTheOther() {
        try (var fixture = SchedulerTestSupport.open()) {
            long first = (TimeUtil.now() / 1000 + 10) * 1000;
            long second = first + 120_000;
            var published = fixture.deploy(SchedulerTestSupport.yaml("uc14-s1-flow", first, second));
            var flow = fixture.flowService().flow(fixture.session(), published.key(), published.version()).orElseThrow();
            assertEquals(2, flow.triggers().size());
            var trigger = assertInstanceOf(ScheduleTrigger.class, flow.triggers().getFirst());
            assertEquals("first", trigger.key());
            assertEquals(SchedulerTestSupport.cron(first), trigger.cron());
            assertEquals("Asia/Shanghai", trigger.timezone());
            assertEquals(SchedulerTestSupport.cron(second),
                assertInstanceOf(ScheduleTrigger.class, flow.triggers().get(1)).cron());
            assertTrue(TimeUtil.now() < first, "配置应在首个计划时间前发布完成");

            // S1 预期：到期前没有实例；无需任何手动启动操作。
            SchedulerTestSupport.assertCountThrough(fixture, 0, first - 100);
            var executions = SchedulerTestSupport.awaitCount(fixture, 1, first + 20_000);
            SchedulerTestSupport.assertCompleted(fixture, flow, executions);
            SchedulerTestSupport.assertCountThrough(fixture, 1, TimeUtil.now() + 1_000);
            assertTrue(TimeUtil.now() < second, "结束观察时第二个条件尚未满足");
            SchedulerTestSupport.deleteAndAwaitCancellation(fixture, flow);
        }
    }

    /** S2：两个独立条件同刻满足，公开查询到两个不同实例且均完整完成。 */
    @Test
    void s2SimultaneousConditionsCreateTwoCompleteExecutions() {
        try (var fixture = SchedulerTestSupport.open()) {
            long due = (TimeUtil.now() / 1000 + 10) * 1000;
            var published = fixture.deploy(SchedulerTestSupport.yaml("uc14-s2-flow", due, due));
            var flow = fixture.flowService().flow(fixture.session(), published.key(), published.version()).orElseThrow();
            assertEquals(2, flow.triggers().size());
            assertNotEquals(flow.triggers().getFirst().key(), flow.triggers().get(1).key());
            for (var definition : flow.triggers()) {
                var trigger = assertInstanceOf(ScheduleTrigger.class, definition);
                assertEquals(SchedulerTestSupport.cron(due), trigger.cron());
                assertEquals("Asia/Shanghai", trigger.timezone());
            }
            assertTrue(TimeUtil.now() < due, "配置应在计划时间前发布完成");
            SchedulerTestSupport.assertCountThrough(fixture, 0, due - 100);

            // S2 预期及结束：两个编号不同的实例均执行完整步骤，且无活动步骤。
            var executions = SchedulerTestSupport.awaitCount(fixture, 2, due + 20_000);
            assertNotEquals(executions.getFirst().id(), executions.get(1).id());
            SchedulerTestSupport.assertCompleted(fixture, flow, executions);
            SchedulerTestSupport.assertCountThrough(fixture, 2, TimeUtil.now() + 1_000);
            assertTrue(TimeUtil.now() < due + 86_400_000, "结束观察早于下一次每日计划");
            SchedulerTestSupport.deleteAndAwaitCancellation(fixture, flow);
        }
    }
}
