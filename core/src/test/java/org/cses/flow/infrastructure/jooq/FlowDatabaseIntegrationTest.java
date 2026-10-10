package org.cses.flow.infrastructure.jooq;

import com.zaxxer.hikari.HikariDataSource;
import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import org.cses.flow.core.services.flows.FlowService;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.junit.jupiter.api.Test;
import org.paas.common.util.StringUtil;
import org.paas.session.Session;
import org.paas.session.User;
import org.x9.jooq.JOOQ;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 通过公开服务验证具名 Flow 库与宿主默认库隔离，所有测试池由创建方关闭。 */
class FlowDatabaseIntegrationTest {
    /**
     * 保存草稿只写入人工建立基线的 Flow 库，关闭应用释放自己的两个具名连接池。
     * @throws SQLException 测试数据库查询或资源释放失败时抛出
     */
    @Test
    void usesOnlyTheManuallyProvisionedNamedFlowDataSource() throws SQLException {
        String url = System.getenv("FLOW_POSTGRES_TEST_URL");
        String defaultUrl = System.getenv("FLOW_POSTGRES_DEFAULT_TEST_URL");
        assumeTrue(url != null && !url.isBlank() && defaultUrl != null && !defaultUrl.isBlank(),
            "FLOW_POSTGRES_TEST_URL and FLOW_POSTGRES_DEFAULT_TEST_URL are required");
        String user = environment("FLOW_POSTGRES_TEST_USER", "flow");
        String password = environment("FLOW_POSTGRES_TEST_PASSWORD", "flow");
        String hostUser = environment("FLOW_POSTGRES_DEFAULT_TEST_USER", user);
        String hostPassword = environment("FLOW_POSTGRES_DEFAULT_TEST_PASSWORD", password);
        String company = "flow-database-integration-" + StringUtil.newId();
        try (var flow = PostgresJooqTestAdapter.dataSource(url, user, password);
             var host = PostgresJooqTestAdapter.dataSource(defaultUrl, hostUser, hostPassword)) {
            assertHostDatabaseHasNoFlowTables(host);
            assertFlowSchemaWasProvisioned(flow);
            HikariDataSource applicationFlow;
            HikariDataSource applicationHost;
            try (var engine = new org.cses.flow.infrastructure.datapilot.FlowDataSourceEngineTestSupport();
                 ApplicationContext context = ApplicationContext.builder()
                     .properties(properties(url, user, password, defaultUrl, hostUser, hostPassword)).build()) {
                context.registerSingleton(org.cses.flow.infrastructure.datapilot.FlowDataSourceEngine.class, engine, null, false);
                context.start();
                applicationFlow = context.getBean(DataSource.class, Qualifiers.byName("flow"))
                    .unwrap(HikariDataSource.class);
                applicationHost = context.getBean(DataSource.class, Qualifiers.byName("default"))
                    .unwrap(HikariDataSource.class);
                JOOQ flowJooq = context.getBean(JOOQ.class, Qualifiers.byName("flow"));
                JOOQ hostJooq = context.getBean(JOOQ.class, Qualifiers.byName("default"));
                assertSame(applicationFlow, flowJooq.createDSLContext().getDataSource());
                assertSame(applicationHost, hostJooq.createDSLContext().getDataSource());
                assertNotSame(applicationFlow, applicationHost);
                FlowService service = context.getBean(FlowService.class);
                var draft = service.save(session(company), PublishFlowCommand.from("""
                    key: database-integration
                    description: Flow datasource integration
                    tasks:
                      - key: start
                        type: org.cses.flow.extensions.log.Log
                        message: test step
                    """));
                assertEquals(draft.id(), service.draft(session(company), draft.key()).orElseThrow().id());
            }
            assertTrue(applicationFlow.isClosed(), "关闭应用必须释放 Flow 连接池");
            assertTrue(applicationHost.isClosed(), "关闭应用必须释放宿主连接池");
            try (Connection connection = flow.getConnection()) {
                assertTrue(tableExists(connection, "flows"));
                assertTrue(tableExists(connection, "task_runs"));
                assertEquals(1, tenantDrafts(connection, company));
            } finally {
                try (Connection connection = flow.getConnection()) {
                    removeTenantDraft(connection, company);
                }
            }
            assertHostDatabaseHasNoFlowTables(host);
        }
    }

    /**
     * 配置两个独立真实库，不开启外部消费者或配置发现。
     * @param url 专属 Flow URL
     * @param user Flow 测试账号
     * @param password Flow 测试凭据
     * @param hostUrl 专属空宿主库 URL
     * @param hostUser 宿主测试账号
     * @param hostPassword 宿主测试凭据
     * @return 仅供此测试上下文使用的配置，不输出凭据
     */
    private static Map<String, Object> properties(String url, String user, String password,
            String hostUrl, String hostUser, String hostPassword) {
        return Map.ofEntries(
            Map.entry("datasources.flow.url", url),
            Map.entry("datasources.flow.driver-class-name", "org.postgresql.Driver"),
            Map.entry("datasources.flow.username", user), Map.entry("datasources.flow.password", password),
            Map.entry("datasources.flow.maximum-pool-size", 2),
            Map.entry("jooq.datasources.flow.sql-dialect", "POSTGRES"),
            Map.entry("datasources.default.url", hostUrl),
            Map.entry("datasources.default.driver-class-name", "org.postgresql.Driver"),
            Map.entry("datasources.default.username", hostUser), Map.entry("datasources.default.password", hostPassword),
            Map.entry("datasources.default.maximum-pool-size", 2),
            Map.entry("jooq.datasources.default.sql-dialect", "POSTGRES"),
            Map.entry("jooq.send-event", false), Map.entry("micronaut.config-client.enabled", false),
            Map.entry("consul.client.registration.enabled", false), Map.entry("consul.client.config.enabled", false),
            Map.entry("grpc.server.enabled", false), Map.entry("thrift.server.enabled", false),
            Map.entry("pulsar.consumer.enabled", false)
        );
    }

    /**
     * 宿主库必须保持没有 Flow 表，不能依靠启动自动建立基线。
     * @param source 调用方拥有的宿主池
     * @throws SQLException 数据库查询失败时抛出
     */
    private static void assertHostDatabaseHasNoFlowTables(DataSource source) throws SQLException {
        try (Connection connection = source.getConnection()) {
            assertFalse(tableExists(connection, "flows"));
            assertFalse(tableExists(connection, "task_runs"));
        }
    }

    /**
     * 确认运行前人工执行的 Flow 基线存在，不在测试中创建业务表。
     * @param source 调用方拥有的 Flow 池
     * @throws SQLException 数据库查询失败时抛出
     */
    private static void assertFlowSchemaWasProvisioned(DataSource source) throws SQLException {
        try (Connection connection = source.getConnection()) {
            assertTrue(tableExists(connection, "flows"), "先执行 gen/sql/flow/001_create_flow_tables.sql");
            assertTrue(tableExists(connection, "task_runs"));
        }
    }

    /**
     * 创建此次租户的公开用户会话。
     * @param company 测试租户身份
     * @return 有当前用户与租户的会话
     */
    private static Session<User> session(String company) {
        User user = new User();
        user.setId("flow-database-user"); user.setCompanyId(company);
        Session<User> session = new Session<>(); session.setUser(user);
        return session;
    }

    /**
     * 只读检查指定公开表。
     * @param connection 借用的连接
     * @param table 测试固定表名
     * @return 表是否存在
     * @throws SQLException 查询失败时抛出
     */
    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, "public." + table);
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getBoolean(1); }
        }
    }

    /**
     * 统计本场景租户的草稿。
     * @param connection 借用的 Flow 连接
     * @param company 本场景独立租户
     * @return 草稿数量
     * @throws SQLException 查询失败时抛出
     */
    private static int tenantDrafts(Connection connection, String company) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM flows WHERE company_id = ? AND draft")) {
            statement.setString(1, company);
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getInt(1); }
        }
    }

    /**
     * 删除此测试自己创建的草稿。
     * @param connection 借用的 Flow 连接
     * @param company 本场景独立租户
     * @throws SQLException 清理失败时抛出
     */
    private static void removeTenantDraft(Connection connection, String company) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM flows WHERE company_id = ? AND draft")) {
            statement.setString(1, company); statement.executeUpdate();
        }
    }

    /**
     * 读取测试环境值，未设置时采用调用方默认值。
     * @param name 环境键
     * @param fallback 默认值
     * @return 非空环境值或默认值，不输出其内容
     */
    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
