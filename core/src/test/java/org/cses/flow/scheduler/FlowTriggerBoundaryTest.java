package org.cses.flow.scheduler;

import org.cses.flow.core.services.executions.WorkflowUcFixture;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.infrastructure.jooq.PostgresJooqTestAdapter;
import org.cses.flow.infrastructure.repositories.triggers.entries.SchedulerEventEntry;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.flow.gen.flow.Tables.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 Core 独立运行的上游匹配与持久化分发幂等边界。 */
class FlowTriggerBoundaryTest {
    /**
     * 默认成功条件按租户和终态匹配，A→B→A不循环，终态重投不匹配后来新增的目标。
     * @throws Exception 等待真实异步状态中断时抛出
     */
    @Test
    void filtersTenantStateAndCyclesWithoutRematchingProcessedEvents() throws Exception {
        try (var fixture = WorkflowUcFixture.openWithProperties(Map.of("flow.scheduler.enabled", false))) {
            var upstream = fixture.deploy(yaml("upstream-boundary", trigger("").replace("flowKey: upstream-boundary", "flowKey: matched-target")));
            var target = fixture.deploy(yaml("matched-target", trigger("")));
            fixture.deploy(yaml("failed-only", trigger("    states: [FAILED]\n")));
            var foreign = fixture.sessionFor("other-company");
            var foreignDraft = fixture.flowService().save(foreign, PublishFlowCommand.from(yaml("foreign-target", trigger(""))));
            fixture.flowService().save(foreign, PublishFlowCommand.from(foreignDraft.key(), false));
            var source = fixture.startAndAwait(upstream);
            assertEquals(State.Type.SUCCESS, source.state().current());
            long deadline = TimeUtil.now() + 15_000;
            while (fixture.executionCount() < 2 && TimeUtil.now() < deadline) new CountDownLatch(1).await(50, TimeUnit.MILLISECONDS);
            assertEquals(2, fixture.executionCount());
            var targetRun = fixture.executionService().executions(fixture.session()).stream()
                .filter(e -> e.flowKey().equals(target.key())).findFirst().orElseThrow();
            assertEquals(State.Type.SUCCESS, fixture.awaitStable(targetRun.id()).state().current());
            assertTrue(fixture.executionService().executions(foreign).isEmpty());
            var dsl = PostgresJooqTestAdapter.fromEnvironment().createDSLContext();
            var stored = dsl.selectFrom(SCHEDULER_EVENTS).where(SCHEDULER_EVENTS.COMPANY_ID.eq(upstream.companyId()))
                .and(SCHEDULER_EVENTS.KIND.eq("FLOW_TERMINATED"))
                .fetchInto(SchedulerEventEntry.class).stream().filter(entry -> entry.to().executionId().equals(source.id()))
                .findFirst().orElseThrow();
            assertNotNull(stored.processedAt);
            fixture.deploy(yaml("later-target", trigger("")));
            var runtime = fixture.bean(TriggerRuntime.class);
            var downstreamTerminal = dsl.selectFrom(SCHEDULER_EVENTS)
                .where(SCHEDULER_EVENTS.COMPANY_ID.eq(upstream.companyId()))
                .and(SCHEDULER_EVENTS.KIND.eq("FLOW_TERMINATED"))
                .fetchInto(SchedulerEventEntry.class).stream()
                .filter(entry -> entry.to().executionId().equals(targetRun.id())).findFirst().orElseThrow();
            runtime.onEvent(downstreamTerminal.to());
            runtime.onEvent(stored.to());
            runtime.onEvent(stored.to());
            assertEquals(1, dsl.fetchCount(TRIGGER_RUNS, TRIGGER_RUNS.COMPANY_ID.eq(upstream.companyId())));
            assertEquals(2, fixture.executionCount());
            for (var execution : fixture.executionService().executions(fixture.session())) {
                assertTrue(execution.activeTaskRuns().isEmpty());
                assertTrue(execution.taskRuns().stream().noneMatch(run -> run.state().isPaused()));
            }
        }
    }

    /**
     * 生成监听同名上游的条件。
     * @param state 可选的 YAML states 行，空字符串表示默认成功
     * @return 完整触发器配置片段
     */
    private static String trigger(String state) {
        return "triggers:\n  - key: from-upstream\n    type: org.cses.flow.extensions.triggers.FlowTrigger\n    flowKey: upstream-boundary\n" + state;
    }

    /**
     * 生成有单个自动完成步骤的最小流程。
     * @param key 本次流程名称
     * @param trigger 可空字符串表示无触发配置，非空时为完整 YAML 段
     * @return 可公开发布的流程文本
     */
    private static String yaml(String key, String trigger) {
        return "key: " + key + "\n" + trigger + "tasks:\n  - key: done\n    type: org.cses.flow.extensions.log.Log\n    message: done\n";
    }
}
