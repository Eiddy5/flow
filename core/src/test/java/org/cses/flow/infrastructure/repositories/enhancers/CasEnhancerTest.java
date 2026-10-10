package org.cses.flow.infrastructure.repositories.enhancers;

import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.infrastructure.repositories.BaseRepository;
import org.cses.flow.infrastructure.jooq.PostgresJooqTestAdapter;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.exception.DataChangedException;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 用另一种业务根组合 CAS，保留旧基类测试的版本、冲突及失败不回填断言。 */
class CasEnhancerTest {
    private org.cses.flow.infrastructure.datapilot.FlowDataSourceEngineTestSupport engine =
        new org.cses.flow.infrastructure.datapilot.FlowDataSourceEngineTestSupport();

    /** 释放当前测试拥有的 Engine 执行器。 */
    @org.junit.jupiter.api.AfterEach
    void closeEngine() { engine.close(); }
    /** 两个业务仓储共用同一增强器，新建及更新按完整身份比较并各自回填版本。 */
    @Test
    void savesAnotherAggregateThroughComposedEnhancer() {
        var sql = new ArrayList<String>();
        var bindings = new ArrayList<List<Object>>();
        DSLContext records = DSL.using(SQLDialect.POSTGRES);
        DSLContext dsl = DSL.using(new MockConnection(context -> {
            sql.add(context.sql());
            bindings.add(List.of(context.bindings()));
            var result = records.newResult(AnotherRepository.LOCK);
            result.add(records.newRecord(AnotherRepository.LOCK).values((long) sql.size() - 1));
            return new MockResult[]{new MockResult(1, result)};
        }), SQLDialect.POSTGRES);
        var enhancer = new CasEnhancer();
        var first = new AnotherRepository(engine, enhancer);
        var second = new AnotherRepository(engine, enhancer);
        var entity = new AnotherEntity();
        first.save(dsl, entity);
        assertEquals(0L, entity.lock);
        second.save(dsl, entity);
        assertEquals(1L, entity.lock);
        assertTrue(sql.getFirst().contains("insert into another_root"));
        assertTrue(sql.getFirst().contains("on conflict (company_id, id) do nothing"));
        assertTrue(sql.getLast().contains("lock = (lock + ?"));
        assertTrue(sql.getLast().contains("company_id = ?"));
        assertTrue(sql.getLast().contains("id = ?"));
        assertTrue(sql.getLast().contains("lock = ?"));
        assertEquals(List.of("changed", 1L, "company", "row", 0L), bindings.getLast());
    }

    /** 零行统一反馈冲突，SQL 故障保留原异常分类，两种失败都不回填输入版本。 */
    @Test
    void failedSavesLeaveTheSnapshotVersionUnchanged() {
        var repository = new AnotherRepository(engine, new CasEnhancer());
        var entity = new AnotherEntity();
        entity.lock = 7L;
        DSLContext missing = DSL.using(new MockConnection(context -> new MockResult[]{
            new MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult(AnotherRepository.LOCK))
        }), SQLDialect.POSTGRES);
        assertEquals("数据已发生变化，请刷新后重试。",
            assertThrows(DataChangedException.class, () -> repository.save(missing, entity)).getMessage());
        assertEquals(7L, entity.lock);
        DSLContext rejected = DSL.using(new MockConnection(context -> {
            throw new SQLException("rejected");
        }), SQLDialect.POSTGRES);
        assertThrows(WorkflowException.class, () -> repository.save(rejected, entity));
        assertEquals(7L, entity.lock);
    }

    /** 负数和最大版本在执行 SQL 前拒绝，不转换为插入或推进版本。 */
    @Test
    void rejectsInvalidAndExhaustedVersions() {
        var repository = new AnotherRepository(engine, new CasEnhancer());
        var entity = new AnotherEntity();
        DSLContext dsl = DSL.using(SQLDialect.POSTGRES);
        entity.lock = -1L;
        assertThrows(IllegalArgumentException.class, () -> repository.save(dsl, entity));
        assertEquals(-1L, entity.lock);
        entity.lock = Long.MAX_VALUE;
        assertThrows(DataChangedException.class, () -> repository.save(dsl, entity));
        assertEquals(Long.MAX_VALUE, entity.lock);
    }

    /** 语句构造不执行 SQL、不修改完整字段 Map，其版本值不能覆盖 CAS 控制值。 */
    @Test
    void buildsUnexecutedStatementsWithoutMutatingFieldsOrIdentity() {
        var executed = new ArrayList<String>();
        DSLContext dsl = DSL.using(new MockConnection(context -> {
            executed.add(context.sql());
            return new MockResult[]{new MockResult(0, null)};
        }), SQLDialect.POSTGRES);
        var fields = new LinkedHashMap<String, Object>();
        fields.put(AnotherRepository.COMPANY.getName(), "tenant");
        fields.put(AnotherRepository.ID.getName(), "root");
        fields.put(AnotherRepository.VALUE, null);
        fields.put(AnotherRepository.LOCK.getName(), 9000L);
        var original = new LinkedHashMap<>(fields);
        var enhancer = new CasEnhancer();
        var insert = enhancer.save(dsl.insertInto(AnotherRepository.ROOT).set(enhancer.fields(fields, AnotherRepository.LOCK)),
            AnotherRepository.COMPANY, AnotherRepository.ID, AnotherRepository.LOCK);
        var updateFields = new LinkedHashMap<String, Object>();
        updateFields.put(AnotherRepository.VALUE, null);
        updateFields.put(AnotherRepository.LOCK.getName(), 9000L);
        var update = enhancer.save(dsl.update(AnotherRepository.ROOT).set(enhancer.fields(updateFields, AnotherRepository.LOCK)),
            AnotherRepository.ID, AnotherRepository.LOCK,
            AnotherRepository.COMPANY.eq("tenant").and(AnotherRepository.ID.eq("root")), 3L);
        assertTrue(executed.isEmpty());
        assertEquals(original, fields);
        assertEquals(9000L, updateFields.get(AnotherRepository.LOCK.getName()));
        assertEquals(java.util.Arrays.asList("tenant", "root", null, 0L), insert.getBindValues());
        assertEquals(java.util.Arrays.asList(null, 1L, "tenant", "root", 3L), update.getBindValues());
        assertTrue(insert.getSQL().contains("on conflict (company_id, id) do nothing"));
        assertTrue(update.getSQL(org.jooq.conf.ParamType.INLINED).contains("\"value\" = null"));
        assertEquals(9000L, original.get(AnotherRepository.LOCK.getName()));
    }

    /**
     * 真实 PG 必须只接收一个版本列：完整字段中的旧值不能造成重复列或双重赋值。
     * @throws SQLException 测试连接或临时表操作失败时抛出
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "FLOW_POSTGRES_TEST_URL", matches = ".+")
    void completeMapsCannotInjectVersionColumnsInRealPostgres() throws SQLException {
        try (var adapter = PostgresJooqTestAdapter.fromEnvironment(); var connection = adapter.connection()) {
            connection.setAutoCommit(false);
            var dsl = DSL.using(connection, SQLDialect.POSTGRES);
            dsl.execute("create temporary table another_root(company_id text, id text, value text, lock bigint, primary key(company_id,id)) on commit drop");
            var fields = new LinkedHashMap<String, Object>();
            fields.put(AnotherRepository.COMPANY.getName(), "tenant"); fields.put(AnotherRepository.ID.getName(), "root");
            fields.put(AnotherRepository.VALUE, null); fields.put(AnotherRepository.LOCK.getName(), 9000L);
            var enhancer = new CasEnhancer();
            var insert = enhancer.save(dsl.insertInto(AnotherRepository.ROOT).set(enhancer.fields(fields, AnotherRepository.LOCK)),
                AnotherRepository.COMPANY, AnotherRepository.ID, AnotherRepository.LOCK);
            var initial = DSL.name("initial").as(insert);
            assertEquals(0L, enhancer.save(dsl.with(initial).select(initial.field(AnotherRepository.LOCK)).from(initial)));
            var updateFields = new LinkedHashMap<String, Object>();
            updateFields.put(AnotherRepository.VALUE, null); updateFields.put(AnotherRepository.LOCK.getName(), 9000L);
            var update = enhancer.save(dsl.update(AnotherRepository.ROOT).set(enhancer.fields(updateFields, AnotherRepository.LOCK)),
                AnotherRepository.ID, AnotherRepository.LOCK,
                AnotherRepository.COMPANY.eq("tenant").and(AnotherRepository.ID.eq("root")), 0L);
            var changed = DSL.name("changed").as(update);
            assertEquals(1L, enhancer.save(dsl.with(changed).select(changed.field(AnotherRepository.LOCK)).from(changed)));
            assertEquals(9000L, fields.get(AnotherRepository.LOCK.getName()));
            assertEquals(9000L, updateFields.get(AnotherRepository.LOCK.getName()));
            assertNull(dsl.select(DSL.field("value", String.class)).from(AnotherRepository.ROOT).fetchOne(0));
            connection.rollback();
        }
    }

    /** 另一种根类型提供自己的复合主键元数据，基础仓储不识别租户字段。 */
    private static class AnotherTable extends org.jooq.impl.TableImpl<org.jooq.Record> {
        private org.jooq.TableField<org.jooq.Record, String> company = createField(DSL.unquotedName("company_id"), org.jooq.impl.SQLDataType.VARCHAR, this);
        private org.jooq.TableField<org.jooq.Record, String> row = createField(DSL.unquotedName("id"), org.jooq.impl.SQLDataType.VARCHAR, this);
        private org.jooq.TableField<org.jooq.Record, String> value = createField(DSL.name("value"), org.jooq.impl.SQLDataType.VARCHAR, this);
        private org.jooq.TableField<org.jooq.Record, Long> lock = createField(DSL.unquotedName("lock"), org.jooq.impl.SQLDataType.BIGINT, this);

        /** 声明测试根表元数据，不操作数据库。 */
        private AnotherTable() { super(DSL.unquotedName("another_root")); }

        /** @return 本测试根的完整复合主键元数据 */
        @Override
        public org.jooq.UniqueKey<org.jooq.Record> getPrimaryKey() {
            return org.jooq.impl.Internal.createUniqueKey(this, DSL.unquotedName("pk_another"),
                new org.jooq.TableField[]{company, row}, true);
        }
    }

    private static class AnotherEntity {
        Long lock;
    }

    private static class AnotherRepository extends BaseRepository {
        private static final String VALUE = "value";
        private static Table<?> ROOT = new AnotherTable();
        private static Field<String> COMPANY = DSL.field("company_id", String.class);
        private static Field<String> ID = DSL.field("id", String.class);
        private static Field<Long> LOCK = DSL.field("lock", Long.class);
        private CasEnhancer enhancer;

        /**
         * 绑定独立根表并组合调用方提供的增强器。
         * @param engine 当前测试拥有的真实 Engine
         * @param enhancer 当前仓储使用的无状态 CAS 增强器，不创建连接
         */
        private AnotherRepository(org.cses.flow.infrastructure.datapilot.FlowDataSourceEngine engine, CasEnhancer enhancer) {
            super(engine, ROOT);
            this.enhancer = enhancer;
        }

        /**
         * 把完整根 SQL 组装后执行，只有成功才回填快照版本。
         * @param dsl 调用方拥有的上下文，不关闭或重新创建连接
         * @param entity 带加载版本的测试根对象，成功后原地回填
         * @throws DataChangedException 版本或身份冲突时抛出
         * @throws WorkflowException 数据库故障时抛出
         */
        private void save(DSLContext dsl, AnotherEntity entity) {
            Map<String, Object> fields = Map.of(COMPANY.getName(), "company", ID.getName(), "row", VALUE, "changed");
            var statement = entity.lock == null
                ? enhancer.save(insertStatement(dsl, enhancer.fields(fields, LOCK)), COMPANY, ID, LOCK)
                : enhancer.save(updateStatement(dsl, enhancer.fields(fields, LOCK)), ID, LOCK, COMPANY.eq("company").and(ID.eq("row")), entity.lock);
            var root = DSL.name("saved").as(statement);
            entity.lock = execute(dsl, context -> enhancer.save(context.with(root).select(root.field(LOCK)).from(root)));
        }
    }
}
