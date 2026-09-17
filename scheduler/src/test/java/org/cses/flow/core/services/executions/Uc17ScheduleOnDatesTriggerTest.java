package org.cses.flow.core.services.executions;

import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.extensions.triggers.ScheduleOnDatesTrigger;
import org.cses.flow.scheduler.SchedulerTestSupport;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** docs/uc/flow/UC-17 用户在指定日期时间启动 Flow.md 的唯一主测试。 */
class Uc17ScheduleOnDatesTriggerTest {
    /** 查询单个未来时间点，确认到期前无实例、到期后完整成功且计划耗尽。 */
    @Test
    void s1SingleDateStartsOnceAndExhaustsSchedule() { verifyDates(false); }

    /** 查询两个未来时间点，确认逐次产生不同实例，第一次先完成且无提前执行。 */
    @Test
    void s2TwoDatesStartSeparateCompletedExecutions() { verifyDates(true); }

    /**
     * 通过公开配置与查询验证指定日期的完整运行。
     * @param multiple true 表示两个日期，false 表示一个日期
     */
    private void verifyDates(boolean multiple) {
        try (var fixture = SchedulerTestSupport.open()) {
            long first = TimeUtil.now() + 8_000;
            long second = first + 8_000;
            var dates = multiple ? List.of(first, second) : List.of(first);
            var flow = fixture.deploy("""
                key: appointed-flow
                triggers:
                  - key: dates
                    type: org.cses.flow.extensions.triggers.ScheduleOnDatesTrigger
                    dates: %s
                tasks:
                  - key: first-step
                    type: org.cses.flow.extensions.log.Log
                    message: first
                  - key: second-step
                    type: org.cses.flow.extensions.log.Log
                    message: second
                """.formatted(dates));
            var selected = fixture.flowService().flow(fixture.session(), flow.key(), flow.version()).orElseThrow();
            assertEquals(dates, ((ScheduleOnDatesTrigger) selected.triggers().getFirst()).dates());
            SchedulerTestSupport.assertCountThrough(fixture, 0, first - 100);
            var firstRuns = SchedulerTestSupport.awaitCount(fixture, 1, first + 5_000);
            SchedulerTestSupport.assertCompleted(fixture, flow, firstRuns);
            if (multiple) {
                assertTrue(TimeUtil.now() < second);
                SchedulerTestSupport.assertCountThrough(fixture, 1, second - 100);
                var all = SchedulerTestSupport.awaitCount(fixture, 2, second + 15_000);
                assertEquals(2, all.stream().map(e -> e.id()).distinct().count());
                SchedulerTestSupport.assertCompleted(fixture, flow, all);
            }
            assertTrue(dates.stream().noneMatch(date -> date > TimeUtil.now()));
            SchedulerTestSupport.assertCountThrough(fixture, dates.size(), TimeUtil.now() + 1_000);
            SchedulerTestSupport.awaitScheduleCount(flow, 0);
            SchedulerTestSupport.deleteAndAwaitCancellation(fixture, flow);
        }
    }
}
