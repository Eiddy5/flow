package org.cses.flow.infrastructure.repositories.postgres;

import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.infrastructure.jooq.PostgresJooqTestAdapter;
import org.cses.flow.infrastructure.repositories.executions.ExecutionRepositoryImpl;
import org.jooq.DSLContext;
import org.jooq.exception.DataChangedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.paas.common.util.StringUtil;
import org.paas.json.JsonFactory;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.flow.gen.flow.Tables.EXECUTIONS;
import static org.flow.gen.flow.Tables.TASK_RUNS;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 PostgreSQL 上验证仓储 CAS；不是业务 UC，也不替代公开入口验收。 */
@EnabledIfEnvironmentVariable(named = "FLOW_POSTGRES_TEST_URL", matches = ".+")
class ExecutionCasIntegrationTest {

    private ExecutionRepositoryImpl repository = new ExecutionRepositoryImpl();
    private DSLContext database = PostgresJooqTestAdapter.fromEnvironment().createDSLContext();
    private String companyId = "cas-test-" + StringUtil.newId();

    /** 初始化已有 JSON 映射边界。 */
    @BeforeAll
    static void initializeJson() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    /** 只清理当前测试租户，保留其他测试和开发数据。 */
    @AfterEach
    void cleanup() {
        database.deleteFrom(TASK_RUNS).where(TASK_RUNS.EXECUTION_ID.in(
                database.select(EXECUTIONS.ID).from(EXECUTIONS)
                        .where(EXECUTIONS.COMPANY_ID.eq(companyId)))).execute();
        database.deleteFrom(EXECUTIONS).where(EXECUTIONS.COMPANY_ID.eq(companyId)).execute();
    }

    /** 两个线程、连接和仓储实例加载相同版本后竞争，只有一个完整聚合可以提交。
     * @throws Exception 并发任务失败或超时
     */
    @Test
    void concurrentLoadedSnapshotsHaveExactlyOneWinner() throws Exception {
        Execution original = create();
        CyclicBarrier loaded = new CyclicBarrier(2);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var futures = List.of(0, 1).stream().map(writer -> threads.submit(() -> {
                ExecutionRepositoryImpl contender = new ExecutionRepositoryImpl();
                return contender.inScope(database, dsl -> {
                    Execution execution = contender.findById(dsl, companyId, original.id()).orElseThrow();
                    assertEquals(0L, lock(dsl, execution.id()));
                    await(loaded);
                    execution.succeedTaskRun(original.taskRuns().getFirst().id(), Map.of("writer", writer));
                    execution.createTaskRun("writer-" + writer, null, Map.of("writer", writer));
                    try {
                        contender.save(dsl, execution);
                        return writer;
                    } catch (DataChangedException conflict) {
                        return -1;
                    }
                });
            })).toList();
            int first = futures.getFirst().get(20, TimeUnit.SECONDS);
            int second = futures.getLast().get(20, TimeUnit.SECONDS);
            assertNotEquals(first == -1, second == -1, "必须恰好一个写入冲突");
            int winner = Math.max(first, second);
            Execution stored = repository.findById(database, companyId, original.id()).orElseThrow();
            assertEquals(1L, lock(database, original.id()));
            assertEquals(original.state(), stored.state());
            assertEquals(2, stored.taskRuns().size());
            assertEquals(Map.of("writer", winner), stored.taskRuns().getFirst().outputs());
            assertEquals("writer-" + winner, stored.taskRuns().getLast().taskId());
            assertEquals(Map.of("writer", winner), stored.taskRuns().getLast().inputs());
        }
    }

    /** 同一数据库事务内版本也逐次递增，重复加载不能替旧对象刷新比较条件。 */
    @Test
    void repeatedFlushAndMultipleLoadsKeepIndependentVersionsWithinOneTransaction() {
        Execution original = create();
        repository.inScope(database, scoped -> scoped.transactionResult(configuration -> {
            DSLContext dsl = configuration.dsl();
            Execution first = repository.findById(dsl, companyId, original.id()).orElseThrow();
            Execution stale = repository.findAll(dsl, companyId).getFirst();
            assertEquals(0L, lock(dsl, original.id()));
            repository.save(dsl, first);
            assertEquals(1L, lock(dsl, original.id()));
            Execution latest = repository.findById(dsl, companyId, original.id()).orElseThrow();
            assertThrows(DataChangedException.class, () -> repository.save(dsl, stale));
            repository.save(dsl, first);
            assertEquals(2L, lock(dsl, original.id()));
            assertThrows(DataChangedException.class, () -> repository.save(dsl, latest));
            assertThrows(DataChangedException.class, () -> repository.save(dsl, first.copy()));
            return null;
        }));
        assertEquals(2L, lock(database, original.id()));
    }

    /** 会话退出立即清理元数据；旧对象、拷贝和无会话写入都不能绕过加载条件。 */
    @Test
    void scopeCompletionClearsTokensAndDetachedSnapshotsCannotBorrowNewLocks() {
        Execution original = create();
        AtomicReference<DSLContext> expired = new AtomicReference<>();
        AtomicReference<DSLContext> derived = new AtomicReference<>();
        AtomicReference<Map<?, ?>> tokens = new AtomicReference<>();
        Execution detached = repository.inScope(database, dsl -> {
            expired.set(dsl);
            derived.set(dsl.configuration().derive().dsl());
            tokens.set((Map<?, ?>) dsl.configuration().data(ExecutionRepositoryImpl.class));
            Execution loaded = repository.findById(dsl, companyId, original.id()).orElseThrow();
            repository.save(dsl, loaded);
            assertEquals(1, tokens.get().size());
            return loaded;
        });
        assertTrue(tokens.get().isEmpty());
        assertNull(expired.get().configuration().data(ExecutionRepositoryImpl.class));
        assertNull(database.configuration().data(ExecutionRepositoryImpl.class));
        assertThrows(IllegalStateException.class, () -> repository.save(expired.get(), detached));
        assertThrows(IllegalStateException.class, () -> repository.save(derived.get(), detached));
        assertThrows(IllegalStateException.class, () -> repository.save(database, detached));
        repository.inScope(database, dsl -> {
            Execution current = repository.findById(dsl, companyId, original.id()).orElseThrow();
            assertThrows(DataChangedException.class, () -> repository.save(dsl, detached));
            assertThrows(DataChangedException.class, () -> repository.save(dsl, original));
            assertTrue(repository.findById(dsl, "another-tenant", original.id()).isEmpty());
            repository.save(dsl, current);
            return null;
        });
        assertEquals(2L, lock(database, original.id()));
    }

    /** 子集合失败时整条 SQL 回滚，失败对象也不能带着缓存版本直接重试。 */
    @Test
    void childFailureRollsBackRootLockAndChildrenAndInvalidatesTheFailedSnapshot() {
        Execution original = create();
        repository.inScope(database, dsl -> {
            Execution changed = repository.findById(dsl, companyId, original.id()).orElseThrow();
            changed.createTaskRun("x".repeat(TASK_RUNS.TASK_ID.getDataType().length() + 1), null, Map.of());
            changed.beginKilling();
            changed.killUnfinishedTaskRuns();
            assertThrows(WorkflowException.class, () -> repository.save(dsl, changed));
            assertEquals(0L, lock(dsl, original.id()));
            Execution retained = repository.findById(dsl, companyId, original.id()).orElseThrow();
            assertEquals(original.state(), retained.state());
            assertEquals(original.taskRuns().stream().map(TaskRun::id).toList(),
                    retained.taskRuns().stream().map(TaskRun::id).toList());
            assertEquals(original.taskRuns().getFirst().state(), retained.taskRuns().getFirst().state());
            assertThrows(DataChangedException.class, () -> repository.save(dsl, changed));
            repository.save(dsl, retained);
            return null;
        });
        assertEquals(1L, lock(database, original.id()));
    }

    /** 子记录 ID 冲突不能把其他聚合的 TaskRun 搬入新根，整个创建必须回滚。 */
    @Test
    void childIdentityConflictCannotMoveAnotherAggregatesTaskRun() {
        Execution original = create();
        Execution conflicting = Execution.rehydrate(
                StringUtil.newId(), companyId, original.creator(), original.createdAt(),
                original.flowKey(), original.flowVersion(), original.inputs(),
                original.generation(), original.state(), original.taskRuns());
        repository.inScope(database, dsl -> {
            assertThrows(WorkflowException.class, () -> repository.save(dsl, conflicting));
            assertTrue(repository.findById(dsl, companyId, conflicting.id()).isEmpty());
            Execution retained = repository.findById(dsl, companyId, original.id()).orElseThrow();
            assertEquals(0L, lock(dsl, original.id()));
            assertEquals(original.taskRuns().getFirst().id(), retained.taskRuns().getFirst().id());
            assertEquals(original.taskRuns().getFirst().state(), retained.taskRuns().getFirst().state());
            assertEquals(0, dsl.fetchCount(TASK_RUNS, TASK_RUNS.EXECUTION_ID.eq(conflicting.id())));
            return null;
        });
    }

    /** 外层事务回滚后清理会话，不能把已回滚的增量版本带入下一次操作。 */
    @Test
    void transactionRollbackAlsoClearsTheScope() {
        Execution original = create();
        AtomicReference<Map<?, ?>> tokens = new AtomicReference<>();
        assertThrows(IllegalStateException.class, () -> repository.inScope(database, scoped -> {
            tokens.set((Map<?, ?>) scoped.configuration().data(ExecutionRepositoryImpl.class));
            return scoped.transactionResult(configuration -> {
                DSLContext dsl = configuration.dsl();
                Execution loaded = repository.findById(dsl, companyId, original.id()).orElseThrow();
                loaded.beginKilling();
                repository.save(dsl, loaded);
                assertEquals(1L, lock(dsl, original.id()));
                throw new IllegalStateException("rollback-test");
            });
        }));
        assertTrue(tokens.get().isEmpty());
        assertEquals(0L, lock(database, original.id()));
        assertEquals(original.state(), repository.findById(database, companyId, original.id()).orElseThrow().state());
    }

    /** 已加载的根被并发删除后，CAS 必须失败，不能以 upsert 复活陈旧快照。 */
    @Test
    void deletedRootCannotBeReinsertedByAStaleSnapshot() {
        Execution original = create();
        repository.inScope(database, dsl -> {
            Execution loaded = repository.findById(dsl, companyId, original.id()).orElseThrow();
            database.deleteFrom(TASK_RUNS).where(TASK_RUNS.EXECUTION_ID.eq(original.id())).execute();
            database.deleteFrom(EXECUTIONS).where(EXECUTIONS.COMPANY_ID.eq(companyId))
                    .and(EXECUTIONS.ID.eq(original.id())).execute();
            assertThrows(DataChangedException.class, () -> repository.save(dsl, loaded));
            assertThrows(DataChangedException.class, () -> repository.save(dsl, loaded));
            assertTrue(repository.findById(dsl, companyId, original.id()).isEmpty());
            assertEquals(0, dsl.fetchCount(TASK_RUNS, TASK_RUNS.EXECUTION_ID.eq(original.id())));
            return null;
        });
    }

    /**
     * 创建只用于验证存储协议的根和运行中的子任务。
     * @return 已提交但不携带加载版本的领域对象
     */
    private Execution create() {
        User user = new User();
        user.setId("cas-user");
        user.setCompanyId(companyId);
        Session<User> session = new Session<>();
        session.setCompanyId(companyId);
        session.setUser(user);
        Execution execution = Execution.create(null, session, "cas-flow", 1L, Map.of("request", "original"));
        execution.start();
        TaskRun taskRun = execution.createTaskRun("cas-task", null, Map.of("step", "original"));
        execution.startTaskRun(taskRun.id());
        repository.inScope(database, dsl -> {
            repository.save(dsl, execution);
            assertEquals(0L, lock(dsl, execution.id()));
            return null;
        });
        return execution;
    }

    /**
     * 查询根行的真实数据库版本。
     * @param dsl 当前数据库上下文
     * @param executionId 精确 Execution 身份
     * @return 已保存版本
     */
    private long lock(DSLContext dsl, String executionId) {
        return dsl.select(EXECUTIONS.LOCK).from(EXECUTIONS)
                .where(EXECUTIONS.COMPANY_ID.eq(companyId)).and(EXECUTIONS.ID.eq(executionId))
                .fetchOne(EXECUTIONS.LOCK);
    }

    /**
     * 让两次真实读取都完成后再开始竞争写入，不依赖 sleep 或调度时序。
     * @param barrier 读取完成屏障
     */
    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CAS test interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("CAS readers did not reach the barrier", exception);
        }
    }
}
