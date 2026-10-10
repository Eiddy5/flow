package org.cses.flow.scheduler;

import org.cses.flow.core.services.executions.WorkflowUcFixture;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.infrastructure.repositories.triggers.entries.TriggerRunEntry;
import org.cses.flow.scheduler.events.SchedulerEvent;
import org.junit.jupiter.api.Test;
import org.paas.common.util.StringUtil;

import java.util.Map;

import static org.flow.gen.flow.Tables.TRIGGER_RUNS;
import static org.junit.jupiter.api.Assertions.*;

/** Core 独立装配的触发发布边界技术验证，不作为 UC 主测试。 */
class TriggerExecutionPublisherTest {
    /** 禁用定时器时仍核验持久化快照并发布，拒绝篡改且重复受理保持唯一实例。 */
    @Test
    void corePublisherRejectsMismatchedSnapshotAndAcceptsOnceWithoutScheduler() {
        try (var fixture = WorkflowUcFixture.openWithProperties(Map.of("flow.scheduler.enabled", false))) {
            var flow = fixture.deploy("""
                key: core-trigger-publisher
                tasks:
                  - key: completed
                    type: org.cses.flow.extensions.log.Log
                    message: completed
                """);
            var event = SchedulerEvent.from("TRIGGER_FIRED", flow.companyId(), StringUtil.newId(),
                flow.key(), flow.version(), "technical-occurrence", TimeUtil.now(), StringUtil.newId(),
                flow.updater().id(), Map.of());
            var dsl = fixture.bean(org.x9.jooq.JOOQ.class, "flow").createDSLContext();
            // 技术协议夹具：构造上游调度器交付的持久化命中事实，不直接改动 Execution 状态。
            dsl.insertInto(TRIGGER_RUNS).set(TriggerRunEntry.from(event).toMap()).execute();
            var publisher = fixture.triggerExecutionPublisher();
            var mismatched = SchedulerEvent.from(event.kind(), event.companyId(), event.id(), event.flowKey(),
                event.flowVersion(), event.triggerKey(), event.scheduledAt(), StringUtil.newId(),
                event.actorId(), event.inputs());
            assertThrows(IllegalArgumentException.class, () -> publisher.send(mismatched));
            assertEquals(0, fixture.executionCount());
            assertNull(dsl.select(TRIGGER_RUNS.ACCEPTED_AT).from(TRIGGER_RUNS)
                .where(TRIGGER_RUNS.ID.eq(event.id())).fetchOne(TRIGGER_RUNS.ACCEPTED_AT));

            publisher.send(event);
            var completed = fixture.awaitStable(event.executionId());
            assertEquals(org.cses.flow.core.domains.flows.State.Type.SUCCESS, completed.state().current());
            assertEquals(flow.version(), completed.flowVersion());
            assertEquals(1, completed.taskRuns().size());
            assertTrue(completed.activeTaskRuns().isEmpty());
            Long acceptedAt = dsl.select(TRIGGER_RUNS.ACCEPTED_AT).from(TRIGGER_RUNS)
                .where(TRIGGER_RUNS.ID.eq(event.id())).fetchOne(TRIGGER_RUNS.ACCEPTED_AT);
            assertNotNull(acceptedAt);
            publisher.send(event);
            assertEquals(1, fixture.executionCount());
            assertEquals(acceptedAt, dsl.select(TRIGGER_RUNS.ACCEPTED_AT).from(TRIGGER_RUNS)
                .where(TRIGGER_RUNS.ID.eq(event.id())).fetchOne(TRIGGER_RUNS.ACCEPTED_AT));
        }
    }
}
