package org.cses.flow.worker;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.repositories.worker.WorkerDelivery;
import org.cses.flow.core.repositories.worker.WorkerRuntimeRepository;
import org.cses.flow.core.services.executions.WorkflowUcFixture;
import org.cses.flow.core.services.executions.RetryBusinessTask;
import org.cses.flow.core.services.executions.SubmittedBusiness;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.executor.WorkerExecutionCoordinator;
import org.flow.gen.flow.Tables;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonObject;
import org.x9.jooq.JOOQ;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 PG 上核验租约 fencing 和原 Attempt 接管，仅为技术协议验证。 */
class WorkerPermitIntegrationTest {
    private static final String WORKER_ENABLED = "flow.worker.enabled";
    private static final String RESULT_ENABLED = "flow.executor.worker-result.enabled";
    private static final String WORKER_KIND = "WORKER";
    private static final String RESULT_KIND = "RESULT";

    /** 当前许可拒绝重复领取，自然到期接管只增加代次，旧 owner/代次不能续租或提交结果。 */
    @Test
    void staleOwnersAndGenerationsCannotRenewOrReport() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.openWithProperties(
            Map.of(WORKER_ENABLED, false, RESULT_ENABLED, false))) {
            var flow = fixture.deploy("""
                key: worker-permit-technical
                tasks:
                  - key: a
                    type: org.cses.flow.extensions.log.Log
                    message: original
                """);
            String id = fixture.startCreated(flow).id();
            var submitted = fixture.awaitExecution(fixture.session(), id,
                e -> e.taskRuns().stream().anyMatch(t -> t.state().is(State.Type.SUBMITTED)));
            String taskRunId = submitted.taskRuns().getFirst().id();
            JOOQ database = fixture.bean(JOOQ.class, "flow");
            var commandRow = database.get(dsl -> dsl.selectFrom(Tables.EXECUTION_MESSAGES)
                .where(Tables.EXECUTION_MESSAGES.EXECUTION_ID.eq(id))
                .and(Tables.EXECUTION_MESSAGES.KIND.eq(WORKER_KIND)).fetchSingle());
            var command = JsonObject.Parse(commandRow.getPayload().data()).asObject(WorkerCommand.class);
            var coordinator = fixture.bean(WorkerExecutionCoordinator.class);
            var runtime = fixture.bean(WorkerRuntimeRepository.class);
            var first = coordinator.start(command, "first-owner", 3000);
            assertNotNull(first.task());
            assertThrows(IllegalStateException.class, () -> coordinator.start(command, "duplicate-owner", 3000));
            long originalStartedAt = fixture.executionService().execution(fixture.session(), id).orElseThrow()
                .requireTaskRun(taskRunId).attempts().getFirst().startedAt();
            while (TimeUtil.now() <= first.delivery().leaseUntilAt()) LockSupport.parkNanos(25_000_000);
            assertThrows(IllegalStateException.class, () -> coordinator.reported(first.delivery(),
                WorkerTaskResult.success(first.task(), Map.of("value", "stale"))));
            var second = coordinator.start(command, "second-owner", 10000);
            assertEquals(first.delivery().attemptNumber(), second.delivery().attemptNumber());
            assertEquals(first.delivery().deliveryGeneration() + 1, second.delivery().deliveryGeneration());
            var wrongOwner = changedPermit(second.delivery(), first.delivery().ownerId(), second.delivery().deliveryGeneration());
            var wrongGeneration = changedPermit(second.delivery(), second.delivery().ownerId(), first.delivery().deliveryGeneration());
            for (var stale : java.util.List.of(first.delivery(), wrongOwner, wrongGeneration)) {
                long renewalNow = TimeUtil.now();
                boolean renewed = renew(database, runtime, stale, renewalNow, renewalNow + 10000);
                assertFalse(renewed);
                assertThrows(IllegalStateException.class, () -> coordinator.reported(stale,
                    WorkerTaskResult.success(second.task(), Map.of("value", "stale"))));
            }
            int results = database.get(dsl -> dsl.fetchCount(Tables.EXECUTION_MESSAGES,
                Tables.EXECUTION_MESSAGES.EXECUTION_ID.eq(id).and(Tables.EXECUTION_MESSAGES.KIND.eq(RESULT_KIND))));
            assertEquals(0, results);
            long renewalNow = TimeUtil.now();
            boolean renewed = renew(database, runtime, second.delivery(), renewalNow, renewalNow + 10000);
            assertTrue(renewed);
            var renewedDelivery = database.get(dsl -> runtime.delivery(dsl, second.delivery().companyId(), id, taskRunId, false)).orElseThrow();
            assertEquals(renewalNow + 10000, renewedDelivery.leaseUntilAt().longValue());
            var actual = fixture.bean(WorkerDispatcher.class).dispatch(second.task());
            coordinator.reported(second.delivery(), actual);
            fixture.restartServerWithProperties(Map.of(WORKER_ENABLED, true, RESULT_ENABLED, true));
            var completed = fixture.awaitExecution(fixture.session(), id, Execution::isTerminal);
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(1, completed.requireTaskRun(taskRunId).attempts().size());
            assertEquals(originalStartedAt, completed.requireTaskRun(taskRunId).attempts().getFirst().startedAt());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (databaseCount(fixture, id) > 0 && System.nanoTime() < deadline) LockSupport.parkNanos(25_000_000);
            assertEquals(0, databaseCount(fixture, id));
        }
    }

    /**
     * 通过写入回调提交续租，并保留更新计数作为 fencing 断言事实。
     * @param database 当前应用的写入组件
     * @param runtime 真实许可仓储
     * @param delivery 待验证的协议许可
     * @param now 当前绝对 UTC 毫秒时间
     * @param until 新的绝对 UTC 到期时间
     * @return 仅更新当前许可成功时为 true
     */
    private boolean renew(JOOQ database, WorkerRuntimeRepository runtime, WorkerDelivery delivery, long now, long until) {
        var updated = new java.util.concurrent.atomic.AtomicBoolean();
        database.run(dsl -> updated.set(runtime.renew(dsl, delivery, now, until)));
        return updated.get();
    }

    /**
     * 未声明重放安全的任务在许可自然到期后拒绝接管，保持原尝试；取消后由真实消费者清理原命令。
     * @throws Exception 本机外部样本无法创建时抛出
     */
    @Test
    void uncertainTaskCannotBeAutomaticallyReplayedAfterLeaseExpires() throws Exception {
        try (SubmittedBusiness business = new SubmittedBusiness();
             WorkflowUcFixture fixture = WorkflowUcFixture.openWithProperties(
                 Map.of(WORKER_ENABLED, false, "flow.test.retry-task", true))) {
            var flow = fixture.deploy("""
                key: worker-uncertain-technical
                tasks:
                  - key: a
                    type: %s
                    endpoint: '%s'
                    message: original
                """.formatted(RetryBusinessTask.class.getName(), business.endpoint("a")));
            String id = fixture.startCreated(flow).id();
            var submitted = fixture.awaitExecution(fixture.session(), id,
                e -> e.taskRuns().stream().anyMatch(t -> t.state().is(State.Type.SUBMITTED)));
            JOOQ database = fixture.bean(JOOQ.class, "flow");
            var commandRow = database.get(dsl -> dsl.selectFrom(Tables.EXECUTION_MESSAGES)
                .where(Tables.EXECUTION_MESSAGES.EXECUTION_ID.eq(id))
                .and(Tables.EXECUTION_MESSAGES.KIND.eq(WORKER_KIND)).fetchSingle());
            var command = JsonObject.Parse(commandRow.getPayload().data()).asObject(WorkerCommand.class);
            var coordinator = fixture.bean(WorkerExecutionCoordinator.class);
            var first = coordinator.start(command, "uncertain-owner", 3000);
            assertFalse(first.task().runnableTask().canReplay());
            while (TimeUtil.now() <= first.delivery().leaseUntilAt()) LockSupport.parkNanos(25_000_000);
            var rejected = assertThrows(IllegalStateException.class,
                () -> coordinator.start(command, "replacement-owner", 10000));
            assertEquals("Uncertain external effect requires task reconciliation before replay", rejected.getMessage());
            var retained = fixture.executionService().execution(fixture.session(), id).orElseThrow()
                .requireTaskRun(submitted.taskRuns().getFirst().id());
            assertEquals(State.Type.RUNNING, retained.state().current());
            assertEquals(1, retained.attempts().size());
            assertTrue(business.calls("a").isEmpty());
            assertEquals(State.Type.KILLED, fixture.cancel(id).state().current());
            fixture.restartServerWithProperties(Map.of(WORKER_ENABLED, true));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (databaseCount(fixture, id) > 0 && System.nanoTime() < deadline) LockSupport.parkNanos(25_000_000);
            assertEquals(0, databaseCount(fixture, id));
            assertTrue(business.calls("a").isEmpty());
        }
    }

    /**
     * 创建只改变 owner 或技术代次的陈旧协议输入，不写入数据库事实。
     * @param current 当前真实许可
     * @param owner 指定输入 owner
     * @param generation 指定输入技术代次
     * @return 用于 fencing 拒绝断言的输入
     */
    private WorkerDelivery changedPermit(WorkerDelivery current, String owner, long generation) {
        return WorkerDelivery.from(current.companyId(), current.executionId(), current.taskRunId(),
            current.attemptNumber(), generation, owner, current.leaseUntilAt(), current.phase(), current.resultId());
    }

    /**
     * 读取重建后应用中的本运行消息数量。
     * @param fixture 当前真实服务
     * @param id 原运行编号
     * @return 未消费消息数量
     */
    private int databaseCount(WorkflowUcFixture fixture, String id) {
        return fixture.bean(JOOQ.class, "flow").get(dsl -> dsl.fetchCount(Tables.EXECUTION_MESSAGES,
            Tables.EXECUTION_MESSAGES.EXECUTION_ID.eq(id)));
    }
}
