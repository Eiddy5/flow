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
                DSLContext dsl = database.configuration().derive().dsl();
                Execution execution = contender.findById(dsl, companyId, original.id()).orElseThrow();
                assertEquals(0L, lock(dsl, execution.id()));
                await(loaded);
                execution.succeedTaskRun(original.taskRuns().getFirst().id(), Map.of("writer", writer));
                execution.createTaskRun("writer-" + writer, null, Map.of("writer", writer));
                try {
                    contender.save(dsl, execution);
                    return writer;
                } catch (DataChangedException conflict) {
                    assertEquals("数据已发生变化，请刷新后重试。", conflict.getMessage());
                    return -1;
                }
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
        database.transaction(configuration -> {
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
            Execution copy = first.copy();
            repository.save(dsl, copy);
            assertEquals(3L, copy.lock());
            assertEquals(2L, first.lock());
            assertThrows(DataChangedException.class, () -> repository.save(dsl, first));
        });
        assertEquals(3L, lock(database, original.id()));
    }

    /** 普通上下文及嵌套事务保存均回填对象版本，提交后可继续使用同一已提交快照。 */
    @Test
    void committedVersionsSurviveTransactionsWithoutScope() {
        Execution original = create();
        database.transaction(configuration -> {
            DSLContext dsl = configuration.dsl();
            repository.save(dsl, original);
            assertEquals(1L, original.lock());
            dsl.transaction(nested -> repository.save(nested.dsl(), original));
            assertEquals(2L, original.lock());
        });
        new ExecutionRepositoryImpl().save(database, original);
        assertEquals(3L, original.lock());
        assertEquals(3L, lock(database, original.id()));
    }

    /** savepoint 回滚不会恢复内存；丢弃被修改对象，重读数据库版本后再保存。 */
    @Test
    void reloadsSnapshotAfterSavepointRollback() {
        Execution original = create();
        database.transaction(configuration -> {
            DSLContext dsl = configuration.dsl();
            repository.save(dsl, original);
            assertThrows(IllegalArgumentException.class, () -> dsl.transaction(nested -> {
                repository.save(nested.dsl(), original);
                throw new IllegalArgumentException("rollback savepoint");
            }));
            assertEquals(2L, original.lock());
            assertEquals(1L, lock(dsl, original.id()));
            Execution reloaded = repository.findById(dsl, companyId, original.id()).orElseThrow();
            assertEquals(1L, reloaded.lock());
            repository.save(dsl, reloaded);
            assertEquals(2L, reloaded.lock());
        });
        assertEquals(2L, lock(database, original.id()));
    }

    /** 跨上下文副本自带版本；保存一个副本不能更新另一个副本的比较条件。 */
    @Test
    void detachedCopiesCarryVersionsAndRejectStaleWrites() {
        Execution original = create();
        Execution detached = repository.findById(database, companyId, original.id()).orElseThrow().copy();
        assertEquals(0L, detached.lock());
        new ExecutionRepositoryImpl().save(database.configuration().derive().dsl(), detached);
        assertEquals(1L, detached.lock());
        assertEquals(0L, original.lock());
        assertThrows(DataChangedException.class, () -> repository.save(database, original));
        Execution current = repository.findById(database, companyId, original.id()).orElseThrow();
        repository.save(database, current);
        assertThrows(DataChangedException.class, () -> repository.save(database, detached));
        assertTrue(repository.findById(database, "another-tenant", original.id()).isEmpty());
        Execution duplicate = Execution.create(original.id(), session(), "cas-flow", 1, Map.of());
        assertNull(duplicate.lock());
        assertEquals("数据已发生变化，请刷新后重试。",
                assertThrows(DataChangedException.class, () -> repository.save(database, duplicate)).getMessage());
        assertNull(duplicate.lock());
        assertEquals(2L, lock(database, original.id()));
    }

    /** 子集合失败时整条 SQL 回滚，内存与数据库版本都不推进；重读后可以保存。 */
    @Test
    void childFailureRollsBackRootAndDoesNotAdvanceObjectLock() {
        Execution original = create();
        DSLContext dsl = database.configuration().derive().dsl();
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
        assertEquals(0L, changed.lock());
        repository.save(dsl, retained);
        assertEquals(1L, lock(database, original.id()));
    }

    /** 准备其他根已占用的新子记录 ID，验证新建失败不改变归属、不回填 lock。 */
    @Test
    void childIdentityConflictCannotMoveAnotherAggregatesTaskRun() {
        Execution original = create();
        Execution conflicting = Execution.create(null, session(), "cas-flow", 1, Map.of());
        conflicting.start();
        TaskRun collision = conflicting.createTaskRun("collision", null, Map.of());
        database.update(TASK_RUNS).set(TASK_RUNS.ID, collision.id())
                .where(TASK_RUNS.EXECUTION_ID.eq(original.id()))
                .and(TASK_RUNS.ID.eq(original.taskRuns().getFirst().id())).execute();
        assertThrows(WorkflowException.class, () -> repository.save(database, conflicting));
        assertNull(conflicting.lock());
        assertTrue(repository.findById(database, companyId, conflicting.id()).isEmpty());
        Execution retained = repository.findById(database, companyId, original.id()).orElseThrow();
        assertEquals(0L, lock(database, original.id()));
        assertEquals(collision.id(), retained.taskRuns().getFirst().id());
        assertEquals(original.taskRuns().getFirst().state(), retained.taskRuns().getFirst().state());
        assertEquals(0, database.fetchCount(TASK_RUNS, TASK_RUNS.EXECUTION_ID.eq(conflicting.id())));
    }

    /** 外层回滚保持数据库原样；内存暂定版本必须丢弃，后续读取获得实际版本。 */
    @Test
    void reloadsSnapshotAfterOuterRollback() {
        Execution original = create();
        assertThrows(IllegalStateException.class, () -> database.transaction(configuration -> {
            DSLContext dsl = configuration.dsl();
            original.beginKilling();
            repository.save(dsl, original);
            assertEquals(1L, original.lock());
            throw new IllegalStateException("rollback-test");
        }));
        assertEquals(0L, lock(database, original.id()));
        Execution reloaded = repository.findById(database, companyId, original.id()).orElseThrow();
        assertEquals(0L, reloaded.lock());
        assertEquals(org.cses.flow.core.domains.flows.State.Type.RUNNING, reloaded.state().current());
        repository.save(database, reloaded);
        assertEquals(1L, reloaded.lock());
    }

    /** 已加载的根被并发删除后，CAS 必须失败，不能以 upsert 复活陈旧快照。 */
    @Test
    void deletedRootCannotBeReinsertedByAStaleSnapshot() {
        Execution original = create();
        DSLContext dsl = database.configuration().derive().dsl();
        Execution loaded = repository.findById(dsl, companyId, original.id()).orElseThrow();
        database.deleteFrom(TASK_RUNS).where(TASK_RUNS.EXECUTION_ID.eq(original.id())).execute();
        database.deleteFrom(EXECUTIONS).where(EXECUTIONS.COMPANY_ID.eq(companyId))
                .and(EXECUTIONS.ID.eq(original.id())).execute();
        assertThrows(DataChangedException.class, () -> repository.save(dsl, loaded));
        assertThrows(DataChangedException.class, () -> repository.save(dsl, loaded));
        assertTrue(repository.findById(dsl, companyId, original.id()).isEmpty());
        assertEquals(0, dsl.fetchCount(TASK_RUNS, TASK_RUNS.EXECUTION_ID.eq(original.id())));
    }

    /** 双聚合竞争只有一个完整提交，继承 ID 不重复进入 task_runs。
     * @throws Exception 并发任务超时或失败
     */
    @Test
    void concurrentReplaysCommitOneDerivedSnapshotAndOneSourceCas() throws Exception {
        Execution original = createPaused();
        String targetId = original.taskRuns().getFirst().id();
        String pauseId = original.pausedTaskRuns().getFirst().id();
        CyclicBarrier loaded = new CyclicBarrier(2);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var futures = List.of(0, 1).stream().map(writer -> threads.submit(() -> {
                ExecutionRepositoryImpl contender = new ExecutionRepositoryImpl();
                DSLContext dsl = database.configuration().derive().dsl();
                Execution source = contender.findById(dsl, companyId, original.id()).orElseThrow();
                Execution derived = source.replay(StringUtil.newId(), session(), pauseId, targetId,
                    "writer-" + writer, List.of(pauseId, targetId));
                assertEquals(1L, source.lock());
                assertNull(derived.lock());
                derived.createTaskRun("new-" + writer, null, Map.of("writer", writer));
                await(loaded);
                try {
                    contender.save(dsl, source, derived);
                    assertEquals(2L, source.lock());
                    assertEquals(0L, derived.lock());
                    return derived.id();
                } catch (DataChangedException conflict) {
                    assertEquals(1L, source.lock());
                    assertNull(derived.lock());
                    assertTrue(contender.findById(dsl, companyId, derived.id()).isEmpty());
                    return "conflict";
                }
            })).toList();
            String first = futures.getFirst().get(20, TimeUnit.SECONDS);
            String second = futures.getLast().get(20, TimeUnit.SECONDS);
            assertNotEquals(first.equals("conflict"), second.equals("conflict"));
            String winner = first.equals("conflict") ? second : first;
            var lineage = repository.findByOriginId(database, companyId, original.id());
            assertEquals(2, lineage.size());
            assertTrue(repository.findByOriginId(database, "another-tenant", original.id()).isEmpty());
            Execution source = repository.findById(database, companyId, original.id()).orElseThrow();
            Execution derived = repository.findById(database, companyId, winner).orElseThrow();
            assertEquals(org.cses.flow.core.domains.flows.State.Type.KILLED, source.state().current());
            assertEquals(2L, lock(database, source.id()));
            assertEquals(0L, lock(database, derived.id()));
            assertEquals(original.id(), derived.origin().parentId());
            assertEquals(original.id(), derived.origin().originId());
            assertEquals(original.taskRuns().stream().map(TaskRun::id).toList(),
                derived.inheritedTaskRuns().stream().map(TaskRun::id).toList());
            assertEquals(1, derived.ownTaskRuns().size());
            assertEquals(1, database.fetchCount(TASK_RUNS, TASK_RUNS.EXECUTION_ID.eq(winner)));
            assertEquals(original.taskRuns().size() + 1, database.fetchCount(TASK_RUNS,
                TASK_RUNS.EXECUTION_ID.in(original.id(), winner)));
            assertEquals(original.taskRuns().getFirst().outputs(), source.taskRuns().getFirst().outputs());
        }
    }

    /** 派生子记录失败时原实例、根版本、子记录和新实例全部保持提交前状态。 */
    @Test
    void derivedChildFailureRollsBackBothAggregatesWithoutAnOuterTransaction() {
        Execution original = createPaused();
        DSLContext dsl = database.configuration().derive().dsl();
        Execution source = repository.findById(dsl, companyId, original.id()).orElseThrow();
        String pause = source.pausedTaskRuns().getFirst().id();
        String target = source.taskRuns().getFirst().id();
        Execution derived = source.replay(StringUtil.newId(), session(), pause, target,
            "invalid-derived-child", List.of(pause, target));
        derived.createTaskRun("x".repeat(TASK_RUNS.TASK_ID.getDataType().length() + 1), null, Map.of());
        assertThrows(WorkflowException.class, () -> repository.save(dsl, source, derived));
        assertEquals(1L, lock(database, original.id()));
        Execution retained = repository.findById(dsl, companyId, original.id()).orElseThrow();
        assertEquals(original.state(), retained.state());
        assertEquals(original.taskRuns().stream().map(TaskRun::state).toList(),
            retained.taskRuns().stream().map(TaskRun::state).toList());
        assertTrue(repository.findById(dsl, companyId, derived.id()).isEmpty());
        assertEquals(0, database.fetchCount(TASK_RUNS, TASK_RUNS.EXECUTION_ID.eq(derived.id())));
        assertEquals(1, repository.findByOriginId(dsl, companyId, original.id()).size());
        assertEquals(1L, source.lock());
        assertNull(derived.lock());
    }

    /** 派生根身份冲突不能提交原实例的停止事实。 */
    @Test
    void existingDerivedIdentityRollsBackSourceTransition() {
        Execution original = createPaused();
        Execution existing = create();
        DSLContext dsl = database.configuration().derive().dsl();
        Execution source = repository.findById(dsl, companyId, original.id()).orElseThrow();
        String pause = source.pausedTaskRuns().getFirst().id();
        String target = source.taskRuns().getFirst().id();
        Execution derived = source.replay(existing.id(), session(), pause, target,
            "identity-conflict", List.of(pause, target));
        assertThrows(WorkflowException.class, () -> repository.save(dsl, source, derived));
        assertEquals(original.state(), repository.findById(dsl, companyId, original.id()).orElseThrow().state());
        assertEquals(existing.state(), repository.findById(dsl, companyId, existing.id()).orElseThrow().state());
        assertEquals(1L, lock(database, original.id()));
        assertEquals(0L, lock(database, existing.id()));
    }

    /** @return 已持久化的目标完成、源 Pause 和并行 Pause 快照 */
    private Execution createPaused() {
        Execution original = create();
        DSLContext dsl = database.configuration().derive().dsl();
        Execution source = repository.findById(dsl, companyId, original.id()).orElseThrow();
        source.succeedTaskRun(source.taskRuns().getFirst().id(), Map.of("value", "original"));
        for (String key : List.of("source-pause", "sibling-pause")) {
            TaskRun pause = source.createTaskRun(key, null, Map.of());
            source.startTaskRun(pause.id());
            source.pauseTaskRun(pause.id());
        }
        source.pause();
        repository.save(dsl, source);
        return source;
    }

    /** @return 本测试租户的有效用户会话 */
    private Session<User> session() {
        User user = new User();
        user.setId("cas-user");
        user.setCompanyId(companyId);
        Session<User> session = new Session<>();
        session.setCompanyId(companyId);
        session.setUser(user);
        return session;
    }

    /**
     * 创建只用于验证存储协议的根和运行中的子任务。
     * @return 已提交且携带数据库版本 0 的领域对象
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
        DSLContext dsl = database.configuration().derive().dsl();
        assertNull(execution.lock());
        assertNull(execution.copy().lock());
        repository.save(dsl, execution);
        assertEquals(0L, execution.lock());
        assertEquals(0L, lock(dsl, execution.id()));
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
