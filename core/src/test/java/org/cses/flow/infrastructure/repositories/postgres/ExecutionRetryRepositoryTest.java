package org.cses.flow.infrastructure.repositories.postgres;

import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.core.services.executions.WorkflowUcFixture;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.executor.ExecutorContext;
import org.cses.flow.executor.ExecutorService;
import org.cses.flow.executor.TaskRetryEvent;
import org.cses.flow.worker.WorkerTaskResult;
import org.jooq.exception.DataChangedException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 真 PostgreSQL outbox 与聚合 CAS 原子性测试，不作为用户操作路径。 */
class ExecutionRetryRepositoryTest {
    /** 未来计划立即可发布；重复保存不重置ACK，陈旧CAS不能写入下一次计划。 */
    @Test
    void persistsFuturePlanAtomicallyAndPublishesOnlyOnce() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.openWithProperties(Map.of("flow.executor.retry.outbox.enabled", false))) {
            var flow = fixture.deploy("""
                key: retry-repository
                tasks:
                  - key: fails
                    type: org.cses.flow.extensions.log.Log
                    message: '{{ outputs.missing.value }}'
                    retry: {type: constant, maxAttempts: 3, intervalMillis: 300000}
                """);
            String id = fixture.startCreated(flow).id();
            var waiting = fixture.awaitExecution(fixture.session(), id,
                e -> e.taskRuns().stream().anyMatch(t -> t.state().is(State.Type.RETRY_WAIT)));
            var repository = fixture.bean(ExecutionRepository.class);
            var database = fixture.bean(org.x9.jooq.JOOQ.class, "flow");
            var pending = database.get(dsl -> repository.pendingRetries(dsl, 100)).stream()
                .filter(e -> e.executionId().equals(id)).findFirst().orElseThrow();
            assertEquals(fixture.session().getCompanyId(), pending.companyId());
            assertEquals(waiting.taskRuns().getFirst().id(), pending.taskRunId());
            assertEquals(2, pending.attemptNumber());
            assertEquals(waiting.taskRuns().getFirst().nextRetryAt(), pending.deliverAt());
            assertTrue(pending.deliverAt() > TimeUtil.now());
            var stale = database.get(dsl -> repository.findById(dsl, pending.companyId(), id).orElseThrow());
            database.run(dsl -> repository.save(dsl, waiting));
            assertEquals(1, database.get(dsl -> repository.pendingRetries(dsl, 100)).stream()
                .filter(e -> e.executionId().equals(id)).count());

            ExecutorContext context = new ExecutorContext(flow, stale);
            ExecutorService executor = new ExecutorService();
            assertTrue(executor.acceptRetry(context, TaskRetryEvent.from(pending.companyId(), id,
                pending.taskRunId(), 2, pending.deliverAt()), pending.deliverAt()));
            executor.process(context);
            var worker = executor.dispatch(context, context.takeWorkerTasks().getFirst()).orElseThrow();
            executor.applyResult(context, WorkerTaskResult.failed(worker, "second failure"));
            assertThrows(DataChangedException.class, () -> database.run(dsl -> repository.save(dsl, stale)));
            assertEquals(1, database.get(dsl -> repository.findById(dsl, pending.companyId(), id).orElseThrow())
                .taskRuns().getFirst().attemptNumber());
            assertEquals(java.util.List.of(pending), database.get(dsl -> repository.pendingRetries(dsl, 100)));

            database.run(dsl -> repository.retryPublished(dsl, pending, TimeUtil.now()));
            database.run(dsl -> repository.retryPublished(dsl, pending, TimeUtil.now()));
            database.run(dsl -> repository.save(dsl, waiting));
            assertTrue(database.get(dsl -> repository.pendingRetries(dsl, 100)).isEmpty());
            assertEquals(State.Type.KILLED, fixture.cancel(id).state().current());
            assertTrue(fixture.executionService().execution(fixture.session(), id).orElseThrow().activeTaskRuns().isEmpty());
        }
    }
}
