package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.extensions.triggers.FlowTrigger;
import org.cses.flow.scheduler.SchedulerTestSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** docs/uc/flow/UC-15 用户通过上游 Flow 完成启动流程.md 的唯一主测试。 */
class Uc15FlowTriggerTest {
    /** 只启动上游一次，查询它和自动产生的目标实例直至完整成功。 */
    @Test
    void s1SuccessfulUpstreamAutomaticallyStartsTarget() {
        try (var fixture = SchedulerTestSupport.open()) {
            var upstream = fixture.deploy("""
                key: trigger-upstream
                tasks:
                  - key: first-step
                    type: org.cses.flow.extensions.log.Log
                    message: first
                  - key: second-step
                    type: org.cses.flow.extensions.log.Log
                    message: second
                """);
            var target = fixture.deploy("""
                key: trigger-downstream
                triggers:
                  - key: upstream-finished
                    type: org.cses.flow.extensions.triggers.FlowTrigger
                    flowKey: trigger-upstream
                    states: [SUCCESS]
                tasks:
                  - key: first-step
                    type: org.cses.flow.extensions.log.Log
                    message: first
                  - key: second-step
                    type: org.cses.flow.extensions.log.Log
                    message: second
                """);
            var selected = fixture.flowService().flow(fixture.session(), target.key(), target.version()).orElseThrow();
            var trigger = (FlowTrigger) selected.triggers().getFirst();
            assertEquals(upstream.key(), trigger.flowKey());
            assertEquals(java.util.List.of(State.Type.SUCCESS), trigger.states());
            assertEquals(0, fixture.executionCount());
            var started = fixture.startAndAwait(upstream);
            assertEquals(State.Type.SUCCESS, started.state().current());
            var all = SchedulerTestSupport.awaitCount(fixture, 2, TimeUtil.now() + 20_000);
            var targetExecutions = all.stream().filter(e -> e.flowKey().equals(target.key())).toList();
            assertEquals(1, targetExecutions.size());
            assertNotEquals(started.id(), targetExecutions.getFirst().id());
            SchedulerTestSupport.assertCompleted(fixture, target, targetExecutions);
            SchedulerTestSupport.assertCompleted(fixture, upstream, java.util.List.of(fixture.awaitStable(started.id())));
            SchedulerTestSupport.assertCountThrough(fixture, 2, TimeUtil.now() + 500);
            SchedulerTestSupport.deleteAndAwaitCancellation(fixture, target);
            SchedulerTestSupport.deleteAndAwaitCancellation(fixture, upstream);
        }
    }
}
