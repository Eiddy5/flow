package org.cses.flow.infrastructure.repositories;

import org.cses.flow.infrastructure.datapilot.FlowDataSourceEngineTestSupport;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.flow.gen.flow.Tables.FLOWS;
import static org.flow.gen.flow.Tables.TASK_RUNS;
import static org.junit.jupiter.api.Assertions.*;

/** 验证通用 CRUD 元数据、主键保护及显式空值，不要求业务租户字段。 */
class BaseRepositoryTest {
    private FlowDataSourceEngineTestSupport engine = new FlowDataSourceEngineTestSupport();

    /** 释放技术测试拥有的索引传输和 Engine 执行器。 */
    @AfterEach
    void closeEngine() { engine.close(); }

    /** 构造器只要求 Engine 和表；没有租户列的生成表也可以复用 CRUD。 */
    @Test
    void requiresOnlyEngineAndTableMetadata() {
        assertDoesNotThrow(() -> new SampleRepository(engine, TASK_RUNS));
        assertThrows(NullPointerException.class, () -> new SampleRepository(null, TASK_RUNS));
        assertThrows(NullPointerException.class, () -> new SampleRepository(engine, null));
    }

    /** 完整 Map 不被修改；插入保留复合主键，更新按生成主键元数据排除两列并保留 NULL。 */
    @Test
    void fullSnapshotWritesPreserveNullsWithoutUpdatingPrimaryKeys() {
        var repository = new SampleRepository(engine, FLOWS);
        var fields = new LinkedHashMap<String, Object>();
        fields.put(FLOWS.COMPANY_ID.getName(), "tenant");
        fields.put(FLOWS.ID.getName(), "row");
        fields.put(FLOWS.DESCRIPTION.getName(), null);
        var original = new LinkedHashMap<>(fields);
        DSLContext dsl = DSL.using(SQLDialect.POSTGRES);
        var insert = repository.newRow(dsl, fields);
        var update = repository.changedRow(dsl, fields);
        assertEquals(original, fields);
        assertEquals(java.util.Arrays.asList("tenant", "row", null), insert.getBindValues());
        assertEquals(java.util.Arrays.asList((Object) null), update.getBindValues());
        assertTrue(update.getSQL(org.jooq.conf.ParamType.INLINED).contains("\"description\" = null"));
        assertFalse(update.getSQL().contains(FLOWS.COMPANY_ID.getName()));
        assertFalse(update.getSQL().contains("\"id\""));
    }

    /** 只有 ID 为主键的生成表更新仍保留普通 execution_id，基类不识别其业务含义。 */
    @Test
    void usesGeneratedPrimaryKeysForTablesWithoutTenantColumns() {
        var repository = new SampleRepository(engine, TASK_RUNS);
        var fields = new LinkedHashMap<String, Object>();
        fields.put(TASK_RUNS.ID.getName(), "task-row");
        fields.put(TASK_RUNS.EXECUTION_ID.getName(), "execution");
        fields.put(TASK_RUNS.ERROR.getName(), null);
        var original = new LinkedHashMap<>(fields);
        var update = repository.changedRow(DSL.using(SQLDialect.POSTGRES), fields);
        assertEquals(java.util.Arrays.asList("execution", null), update.getBindValues());
        assertEquals(original, fields);
        assertFalse(update.getSQL().contains("\"id\""));
        assertTrue(update.getSQL().contains(TASK_RUNS.EXECUTION_ID.getName()));
    }

    private static class SampleRepository extends BaseRepository {
        /**
         * 只绑定 Engine 和目标表，不取得连接。
         * @param engine 当前测试拥有的真实 Engine；为空用于验证拒绝
         * @param table 生成表元数据；为空用于验证拒绝
         */
        private SampleRepository(org.cses.flow.infrastructure.datapilot.FlowDataSourceEngine engine, Table<?> table) {
            super(engine, table);
        }

        /**
         * 暴露尚未执行的插入构造。
         * @param dsl 调用方上下文
         * @param fields 不修改的完整字段 Map
         * @return 尚未执行的插入 SQL
         */
        private org.jooq.Query newRow(DSLContext dsl, Map<String, Object> fields) { return insertStatement(dsl, fields); }

        /**
         * 暴露尚未执行的更新构造，实际调用方需追加 WHERE。
         * @param dsl 调用方上下文
         * @param fields 不修改的完整字段 Map
         * @return 排除生成主键字段的未执行更新
         */
        private org.jooq.Query changedRow(DSLContext dsl, Map<String, Object> fields) { return updateStatement(dsl, fields); }
    }
}
