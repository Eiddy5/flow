package org.flow.integration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

import org.flow.core.migration.MigrationHistoryStore;
import org.flow.core.migration.MigrationLock;
import org.flow.core.migration.MigrationRunner;
import org.flow.core.migration.MigrationScript;
import io.micronaut.context.ApplicationContext;
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(startApplication = false, transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlowMigrationTest implements TestPropertyProvider {
    @Inject ApplicationContext context;
    @Inject @Named("flow") DataSource flowSource;
    @Inject @Named("default") DataSource controlSource;

    /**
     * 明确开启自动迁移时由原生启动逻辑初始化 flow schema，default 对照库不建表。
     * @throws Exception 数据库验证失败时抛出
     */
    @Test
    void initializesSchemaOnlyWhenAutomaticMigrationIsExplicitlyEnabled() throws Exception {
        Map<String, Object> properties = new HashMap<>(new FlowJdbcTest().getProperties());
        properties.put("datasources.flow.schema", "flow_auto_migration_acceptance");
        properties.put("flow.migration.auto", true);
        try (var automatic = ApplicationContext.run(properties, "test")) {
            DataSource source = automatic.getBean(DataSource.class,
                io.micronaut.inject.qualifiers.Qualifiers.byName("flow"));
            try (var connection = DelegatingDataSource.unwrapDataSource(source).getConnection();
                 var controlConnection = DelegatingDataSource.unwrapDataSource(controlSource).getConnection()) {
                var database = DSL.using(connection, SQLDialect.POSTGRES);
                assertNotNull(database.fetchValue("select to_regclass('flows')"));
                assertNotNull(database.fetchValue("select to_regclass('flow_migration_history')"));
                assertNull(DSL.using(controlConnection, SQLDialect.POSTGRES)
                    .fetchValue("select to_regclass('flows')"));
            }
        } finally {
            try (var connection = java.sql.DriverManager.getConnection(
                    System.getenv("FLOW_TEST_URL"), "flow", "flow")) {
                DSL.using(connection).execute("drop schema if exists flow_auto_migration_acceptance cascade");
            }
        }
    }

    /**
     * 使用独立 schema 验证完整上游建库，避免与最小队列测试相互覆盖。
     * @return 同时包含 flow 和 default 的标准数据源配置
     */
    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>(new FlowJdbcTest().getProperties());
        properties.put("datasources.flow.schema", "flow_migration_acceptance");
        return properties;
    }

    /**
     * 验证全库建表、历史、数据库锁及冷启动后重复运行始终使用 flow。
     * @throws Exception 数据库或上游迁移失败时使测试失败
     */
    @Test
    void initializesFullSchemaExplicitlyAndRechecksItAfterColdStart() throws Exception {
        try (var flowConnection = DelegatingDataSource.unwrapDataSource(flowSource).getConnection();
             var controlConnection = DelegatingDataSource.unwrapDataSource(controlSource).getConnection()) {
            var flow = DSL.using(flowConnection, SQLDialect.POSTGRES);
            var control = DSL.using(controlConnection, SQLDialect.POSTGRES);
            assertNull(flow.fetchValue("select to_regclass('flow_migration_history')"),
                "启动 Context 不能自动迁移");
            assertEquals(1, context.getBeansOfType(MigrationHistoryStore.class).size());
            assertInstanceOf(org.flow.repository.postgres.migration.PostgresMigrationHistoryStore.class,
                context.getBean(MigrationHistoryStore.class));
            assertEquals(1, context.getBeansOfType(MigrationLock.class).size());
            var lock = context.getBean(MigrationLock.class);
            lock.acquire();
            try {
                assertEquals(1L, flow.fetchValue("select count(*) from pg_locks where locktype = 'advisory' " +
                    "and database = (select oid from pg_database where datname = current_database())"));
                assertEquals(0L, control.fetchValue("select count(*) from pg_locks where locktype = 'advisory' " +
                    "and database = (select oid from pg_database where datname = current_database())"));
            } finally {
                lock.release();
            }
            try {
                var runner = context.getBean(MigrationRunner.class);
                var pending = runner.pendingScripts();
                assertEquals(context.getBeansOfType(MigrationScript.class).size(), pending.size());
                assertNotNull(flow.fetchValue("select to_regclass('flow_migration_history')"),
                    "显式查询计划会按上游协议创建历史表");
                assertNull(flow.fetchValue("select to_regclass('flows')"), "查询计划不能执行业务建表脚本");
                runner.runOrFailIfLocked();
                for (String table : List.of("flows", "executions", "queues", "triggers", "logs", "execution_outputs")) {
                    assertNotNull(flow.fetchValue("select to_regclass(?)", table), table);
                    assertNull(control.fetchValue("select to_regclass(?)", table), table);
                }
                var history = flow.fetch("select * from flow_migration_history order by script_id");
                assertEquals(context.getBeansOfType(MigrationScript.class).size(), history.size());
                assertEquals(Set.of(
                    "0-init", "0-init-queue", "2.0.01-schema", "2.0.02-queue", "2.0.03-triggers",
                    "2.0.06-widen-logs-postgres", "2.0.09-execution-outputs", "2.0.12-fix-state-duration",
                    "2.0.26-purge-legacy-worker-job-running", "2.1.02-trigger-source-disabled"
                ), new HashSet<>(history.getValues("script_id", String.class)),
                    "必须执行保留的基线与升级链路，不注册已退出功能的迁移");
                assertTrue(history.getValues("success", Boolean.class).stream().allMatch(Boolean::booleanValue));
                assertNull(control.fetchValue("select to_regclass('flow_migration_history')"));

                try (var reopened = ApplicationContext.run(new HashMap<String, Object>(getProperties()), "test")) {
                    reopened.getBean(MigrationRunner.class).runOrFailIfLocked();
                    assertTrue(reopened.getBean(MigrationRunner.class).pendingScripts().isEmpty());
                }
                assertEquals(history, flow.fetch("select * from flow_migration_history order by script_id"),
                    "新的迁移器必须验证已应用脚本，不得重写历史或重复执行");
                assertEquals(1, flow.fetchValue("select 1", Integer.class));
                assertEquals(1, control.fetchValue("select 1", Integer.class));
            } finally {
                flow.execute("drop schema if exists flow_migration_acceptance cascade");
            }
        }
    }
}
