package org.cses.flow.infrastructure.datapilot;

import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.infrastructure.jooq.FlowJooqTestConfiguration;
import org.cses.flow.infrastructure.jooq.PostgresJooqTestAdapter;
import org.cses.flow.infrastructure.repositories.BaseRepository;
import org.cses.flow.infrastructure.repositories.flows.FlowRepositoryImpl;
import org.cses.flow.infrastructure.repositories.flows.entries.FlowEntry;
import org.flow.gen.flow.Public;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.InvalidResultException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.paas.common.util.StringUtil;
import org.paas.json.JsonFactory;
import org.paas.session.Session;
import org.paas.session.User;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.flow.gen.flow.Tables.FLOWS;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实原生 Handler 验证身份筛选、NULL、最新行及借用事务；不替代用户 UC。 */
@EnabledIfEnvironmentVariable(named = "FLOW_POSTGRES_TEST_URL", matches = ".+")
class FlowDataSourceEngineIntegrationTest {
    /** 为生产 Entry 的审计和 JSON 字段初始化已有 JSON 边界。 */
    @BeforeAll
    static void initializeJson() { JsonFactory.instance = JsonMapper.createDefault(); }

    /**
     * 全部生成表都注册在具名 Flow 源，原生 CRUD 保留同 ID 的租户隔离和显式 NULL。
     * @throws SQLException 借用测试连接失败时抛出
     */
    @Test
    void nativeHandlersKeepTenantConditionsNullsAndInputMaps() throws SQLException {
        try (var adapter = PostgresJooqTestAdapter.fromEnvironment(); var connection = adapter.connection()) {
            connection.setAutoCommit(false);
            var engine = adapter.engine();
            var source = engine.dbDataSource(DataSourceKey.POSTGRESQL);
            assertEquals("flow", source.getName());
            Public.PUBLIC.getTables().forEach(table -> assertNotNull(source.collection(table.getName())));
            var repository = new SampleRepository(engine);
            var dsl = FlowJooqTestConfiguration.configure(DSL.using(connection, SQLDialect.POSTGRES));
            String company = "engine-" + StringUtil.newId();
            String row = StringUtil.newId();
            var first = fields(company, row, 1);
            first.put(FLOWS.DELETED_AT.getName(), 7_000L);
            var original = new LinkedHashMap<>(first);
            repository.insert(dsl, first);
            assertEquals("[]", dsl.select(FLOWS.INPUTS).from(FLOWS).where(FLOWS.COMPANY_ID.eq(company)
                .and(FLOWS.ID.eq(row))).fetchOne(FLOWS.INPUTS).data(), "原生创建必须保留 inputs 数组形状");
            assertEquals("[]", dsl.select(FLOWS.OUTPUTS).from(FLOWS).where(FLOWS.COMPANY_ID.eq(company)
                .and(FLOWS.ID.eq(row))).fetchOne(FLOWS.OUTPUTS).data(), "原生创建必须保留 outputs 数组形状");
            var duplicate = new LinkedHashMap<>(first);
            duplicate.put(FLOWS.DESCRIPTION.getName(), "must not overwrite");
            duplicate.put(FLOWS.DELETED_AT.getName(), 99_000L);
            repository.insertIfAbsent(dsl, duplicate);
            var other = fields(company + "-other", row, 1);
            other.put(FLOWS.DELETED_AT.getName(), 9_000L);
            repository.insert(dsl, other);
            assertEquals(company, repository.row(dsl, company, row).companyId);
            assertEquals("initial", repository.row(dsl, company, row).description);
            assertEquals(7_000L, repository.row(dsl, company, row).deletedAt);
            assertEquals(company + "-other", repository.row(dsl, company + "-other", row).companyId);
            assertNull(repository.row(dsl, company + "-absent", row));
            var changes = new LinkedHashMap<String, Object>();
            changes.put(FLOWS.DESCRIPTION.getName(), "updated");
            changes.put(FLOWS.DELETED_AT.getName(), null);
            changes.put(FLOWS.COMPANY_ID.getName(), "must not change tenant");
            changes.put(FLOWS.ID.getName(), null);
            var unchanged = new LinkedHashMap<>(changes);
            repository.change(dsl, company + "-absent", row, changes);
            assertEquals("initial", repository.row(dsl, company, row).description);
            assertEquals("initial", repository.row(dsl, company + "-other", row).description);
            repository.change(dsl, company, row, changes);
            assertEquals("updated", repository.row(dsl, company, row).description);
            assertNull(repository.row(dsl, company, row).deletedAt);
            assertEquals("initial", repository.row(dsl, company + "-other", row).description);
            assertEquals(9_000L, repository.row(dsl, company + "-other", row).deletedAt);
            repository.remove(dsl, company, row);
            assertNull(repository.row(dsl, company, row));
            assertNotNull(repository.row(dsl, company + "-other", row));
            assertEquals(original, first);
            assertEquals(unchanged, changes);
            assertTrue(engine.queries() >= 8);
            assertEquals(5, engine.updates());
            assertEquals(1, engine.deletes());
            connection.rollback();
        }
    }

    /**
     * 普通创建的复合主键冲突保留数据库异常，回滚保存点后原行没有被覆盖。
     * @throws SQLException 保存点或连接操作失败时抛出
     */
    @Test
    void ordinaryCreateConflictDoesNotOverwriteTheExistingRow() throws SQLException {
        try (var adapter = PostgresJooqTestAdapter.fromEnvironment(); var connection = adapter.connection()) {
            connection.setAutoCommit(false);
            var repository = new SampleRepository(adapter.engine());
            var dsl = FlowJooqTestConfiguration.configure(DSL.using(connection, SQLDialect.POSTGRES));
            String company = "engine-conflict-" + StringUtil.newId();
            String row = StringUtil.newId();
            var first = fields(company, row, 1);
            repository.insert(dsl, first);
            var duplicate = new LinkedHashMap<>(first);
            duplicate.put(FLOWS.DESCRIPTION.getName(), "must not overwrite");
            var original = new LinkedHashMap<>(duplicate);
            var savepoint = connection.setSavepoint();
            assertThrows(org.jooq.exception.DataAccessException.class, () -> repository.insert(dsl, duplicate));
            connection.rollback(savepoint);
            assertEquals("initial", repository.row(dsl, company, row).description);
            assertEquals(original, duplicate);
            connection.rollback();
        }
    }

    /**
     * 原生查询保留筛选条件及 pageSize，仓储选择本租户最新版本，多行唯一读取被拒绝。
     * @throws SQLException 借用连接失败时抛出
     */
    @Test
    void latestQueryKeepsPageSizeAndTenantAcrossSeveralVersions() throws SQLException {
        try (var adapter = PostgresJooqTestAdapter.fromEnvironment(); var connection = adapter.connection()) {
            connection.setAutoCommit(false);
            var engine = adapter.engine();
            var repository = new SampleRepository(engine);
            var dsl = FlowJooqTestConfiguration.configure(DSL.using(connection, SQLDialect.POSTGRES));
            String company = "engine-latest-" + StringUtil.newId();
            for (long version : new long[]{1, 2, 3}) repository.insert(dsl, fields(company, StringUtil.newId(), version));
            repository.insert(dsl, fields(company + "-other", StringUtil.newId(), 99));
            assertEquals(3, repository.count(dsl, company));
            assertEquals(1, repository.count(dsl, company + "-other"));
            assertEquals(0, repository.count(dsl, company + "-absent"));
            assertEquals(3, repository.countWithPageSize(dsl, company));
            var actual = new FlowRepositoryImpl(engine).findDraftByFlowId(dsl, FlowId.from(company, "engine-flow")).orElseThrow();
            assertEquals(3L, actual.version());
            assertEquals(company, actual.companyId());
            assertThrows(InvalidResultException.class, () -> repository.ambiguous(dsl, company));
            connection.rollback();
        }
    }

    /**
     * Engine 成功与异常都不得提交、回滚或关闭外部连接，不改变调用方渲染设置。
     * @throws SQLException 连接状态读取失败时抛出
     */
    @Test
    void borrowedSessionsPreserveOwnerTransactionConnectionAndSettings() throws SQLException {
        try (var adapter = PostgresJooqTestAdapter.fromEnvironment(); var connection = adapter.connection()) {
            connection.setAutoCommit(false);
            var engine = adapter.engine();
            var repository = new SampleRepository(engine);
            var dsl = FlowJooqTestConfiguration.configure(DSL.using(connection, SQLDialect.POSTGRES));
            dsl.settings().withRenderCatalog(true).withRenderSchema(true);
            String company = "engine-transaction-" + StringUtil.newId();
            String row = StringUtil.newId();
            repository.insert(dsl, fields(company, row, 1));
            assertNotNull(repository.row(dsl, company, row));
            assertEquals(0, (int) adapter.get(observer -> observer.fetchCount(FLOWS, FLOWS.COMPANY_ID.eq(company))));
            var expected = new IllegalStateException("engine-owned-transaction-probe");
            assertSame(expected, assertThrows(IllegalStateException.class, () -> repository.failed(dsl, company, row, expected)));
            assertFalse(connection.isClosed());
            assertFalse(connection.getAutoCommit());
            assertEquals("before failure", repository.row(dsl, company, row).description);
            assertTrue(dsl.settings().isRenderCatalog());
            assertTrue(dsl.settings().isRenderSchema());
            assertEquals(1, engine.executions());
            connection.rollback();
            assertEquals(0, (int) adapter.get(observer -> observer.fetchCount(FLOWS, FLOWS.COMPANY_ID.eq(company))));
        }
    }

    /**
     * 构造包含合法审计和 JSON 字段的完整草稿行，不改变生产 Schema。
     * @param company 当前测试租户
     * @param row 技术行身份
     * @param version 正版本号
     * @return 全字段独立 Map，显式含 NULL
     */
    private static Map<String, Object> fields(String company, String row, long version) {
        User user = new User();
        user.setId("engine-user"); user.setCompanyId(company);
        Session<User> session = new Session<>(); session.setUser(user);
        Flow flow = Flow.create(session, "engine-flow", "initial", Map.of(), List.of(), List.of(), "key: engine-flow");
        return FlowEntry.from(flow, row, version).buildInsertMap();
    }

    /** 暴露基础仓储的实际原生 CRUD 边界，所有操作仍使用生产 Engine。 */
    private static class SampleRepository extends BaseRepository {
        /**
         * 绑定生产 Flow 表，不获取连接。
         * @param engine 当前测试拥有的真实 Engine
         */
        private SampleRepository(FlowDataSourceEngine engine) { super(engine, FLOWS); }
        /**
         * 插入完整行。
         * @param dsl 借用的外部事务
         * @param fields 不修改的完整字段
         */
        private void insert(DSLContext dsl, Map<String, Object> fields) { create(dsl, fields); }
        /**
         * 相同复合身份的重复插入被忽略。
         * @param dsl 借用的外部事务
         * @param fields 不修改的完整字段
         */
        private void insertIfAbsent(DSLContext dsl, Map<String, Object> fields) { createIfAbsent(dsl, fields); }
        /**
         * 读取完整身份行。
         * @param dsl 借用的外部事务
         * @param company 非空白租户
         * @param row 非空白行身份
         * @return 新 Entry，未命中为空
         */
        private FlowEntry row(DSLContext dsl, String company, String row) { return readOne(query(dsl, FLOWS.COMPANY_ID.eq(company).and(FLOWS.ID.eq(row))), FlowEntry.class); }

        /**
         * 按业务提供的租户条件统计复合主键的实际行数。
         * @param dsl 借用的外部事务
         * @param company 当前测试租户
         * @return 原生 Engine 查询返回的行数
         */
        private long count(DSLContext dsl, String company) { return query(dsl, FLOWS.COMPANY_ID.eq(company)).count(); }

        /**
         * 列表分页参数不应限制总行数计数。
         * @param dsl 借用的外部事务
         * @param company 当前测试租户
         * @return 相同过滤条件下的完整数量
         */
        private long countWithPageSize(DSLContext dsl, String company) { return query(dsl, FLOWS.COMPANY_ID.eq(company)).pageSize(1).count(); }
        /**
         * 用唯一读取检查多行查询。
         * @param dsl 借用的事务
         * @param company 当前租户
         * @return 多行时不返回
         * @throws InvalidResultException 当前租户具有多行时抛出
         */
        private FlowEntry ambiguous(DSLContext dsl, String company) { return readOne(query(dsl, FLOWS.COMPANY_ID.eq(company)), FlowEntry.class); }
        /**
         * 原生更新指定身份的字段，保留 NULL。
         * @param dsl 借用事务
         * @param company 当前租户
         * @param row 当前行身份
         * @param fields 不修改的更新字段
         */
        private void change(DSLContext dsl, String company, String row, Map<String, Object> fields) { updateFields(dsl, FLOWS, FLOWS.COMPANY_ID.eq(company).and(FLOWS.ID.eq(row)), fields); }
        /**
         * 原生删除指定身份。
         * @param dsl 借用事务
         * @param company 当前租户
         * @param row 当前行身份
         */
        private void remove(DSLContext dsl, String company, String row) { deleteRows(dsl, FLOWS.COMPANY_ID.eq(company).and(FLOWS.ID.eq(row))); }
        /**
         * 在执行器写入后抛出原异常，交给调用方决定事务结束。
         * @param dsl 外部事务
         * @param company 当前租户
         * @param row 当前行身份
         * @param failure 需要保留身份的异常
         * @throws IllegalStateException 始终抛出提供的异常
         */
        private void failed(DSLContext dsl, String company, String row, IllegalStateException failure) {
            execute(dsl, borrowed -> {
                borrowed.update(FLOWS).set(FLOWS.DESCRIPTION, "before failure").where(FLOWS.COMPANY_ID.eq(company).and(FLOWS.ID.eq(row))).execute();
                throw failure;
            });
        }
    }
}
