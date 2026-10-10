package org.cses.flow.infrastructure.jooq;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.configuration.jdbc.hikari.DatasourceConfiguration;
import io.micronaut.configuration.jdbc.hikari.HikariUrlDataSource;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.inject.Singleton;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.cses.flow.executor.DefaultExecutor;
import org.cses.flow.infrastructure.datapilot.FlowDataSourceEngine;
import org.cses.flow.infrastructure.datapilot.FlowDataSourceEngineTestSupport;
import org.junit.jupiter.api.Test;
import org.x9.jooq.JOOQ;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class FlowNamedDataSourceTest {

    /** 未声明 Flow 数据源时，不借用宿主默认库创建 Flow 门面。 */
    @Test
    void leavesFlowDatabaseAbsentWhenNamedDatasourceIsNotConfigured() {
        Map<String, Object> properties = Map.ofEntries(
            Map.entry("micronaut.config-client.enabled", false),
            Map.entry("consul.client.registration.enabled", false),
            Map.entry("consul.client.config.enabled", false),
            Map.entry("grpc.server.enabled", false),
            Map.entry("thrift.server.enabled", false),
            Map.entry("pulsar.consumer.enabled", false)
        );

        try (var engine = new FlowDataSourceEngineTestSupport();
             ApplicationContext context = startContext(properties, engine, false)) {
            assertFalse(context.findBean(
                DataSource.class,
                Qualifiers.byName(FlowDatabase.DATA_SOURCE_NAME)
            ).isPresent());
            assertFalse(context.findBean(
                org.jooq.Configuration.class,
                Qualifiers.byName(FlowDatabase.DATA_SOURCE_NAME)
            ).isPresent());
            assertFalse(context.findBean(
                JOOQ.class,
                Qualifiers.byName(FlowDatabase.DATA_SOURCE_NAME)
            ).isPresent());
        }
    }

    /**
     * 同名 DataSource、配置和 JOOQ 绑定同一池，宿主池保持独立。
     * @throws SQLException 连接池 unwrap 失败时抛出
     */
    @Test
    void createsNamedDatabaseBeansFromStandardDatasourceProperties()
        throws SQLException {
        Map<String, Object> properties = Map.ofEntries(
            Map.entry(
                "datasources.flow.url",
                "jdbc:postgresql://127.0.0.1:1/flow"
            ),
            Map.entry("datasources.flow.username", "flow"),
            Map.entry("datasources.flow.password", "flow"),
            Map.entry(
                "datasources.flow.driver-class-name",
                "org.postgresql.Driver"
            ),
            Map.entry("datasources.flow.maximum-pool-size", 7),
            Map.entry("datasources.flow.minimum-idle", 1),
            Map.entry("datasources.flow.connection-timeout", 250),
            Map.entry("datasources.flow.initialization-fail-timeout", -1),
            // This unit test has no live PostgreSQL. Production omits this
            // test-only shortcut and lets Micronaut detect the dialect.
            Map.entry("jooq.datasources.flow.sql-dialect", "POSTGRES"),
            Map.entry(
                "datasources.default.url",
                "jdbc:postgresql://127.0.0.1:1/default"
            ),
            Map.entry(
                "datasources.default.driver-class-name",
                "org.postgresql.Driver"
            ),
            Map.entry("datasources.default.username", "default"),
            Map.entry("datasources.default.password", "default"),
            Map.entry("datasources.default.connection-timeout", 250),
            Map.entry("datasources.default.initialization-fail-timeout", -1),
            Map.entry("jooq.datasources.default.sql-dialect", "POSTGRES"),
            Map.entry("micronaut.config-client.enabled", false),
            Map.entry("consul.client.registration.enabled", false),
            Map.entry("consul.client.config.enabled", false),
            Map.entry("grpc.server.enabled", false),
            Map.entry("thrift.server.enabled", false),
            Map.entry("pulsar.consumer.enabled", false)
        );

        try (var engine = new FlowDataSourceEngineTestSupport();
             ApplicationContext context = startContext(properties, engine, false)) {
            DatasourceConfiguration configuration = context.getBean(
                DatasourceConfiguration.class,
                Qualifiers.byName(FlowDatabase.DATA_SOURCE_NAME)
            );

            assertEquals(
                "jdbc:postgresql://127.0.0.1:1/flow",
                configuration.getJdbcUrl()
            );
            assertEquals("flow", configuration.getUsername());
            assertEquals(7, configuration.getMaximumPoolSize());
            assertEquals(1, configuration.getMinimumIdle());
            DataSource flowDataSource = context.getBean(
                DataSource.class,
                Qualifiers.byName(FlowDatabase.DATA_SOURCE_NAME)
            );
            DataSource defaultDataSource = context.getBean(
                DataSource.class,
                Qualifiers.byName("default")
            );
            org.jooq.Configuration flowJooq = context.getBean(
                org.jooq.Configuration.class,
                Qualifiers.byName(FlowDatabase.DATA_SOURCE_NAME)
            );
            org.jooq.Configuration defaultJooq = context.getBean(
                org.jooq.Configuration.class,
                Qualifiers.byName("default")
            );
            assertEquals(SQLDialect.POSTGRES, flowJooq.dialect());
            assertEquals(SQLDialect.POSTGRES, defaultJooq.dialect());
            assertNotSame(flowJooq, defaultJooq);
            JOOQ flowJooqFacade = context.getBean(
                JOOQ.class,
                Qualifiers.byName(FlowDatabase.DATA_SOURCE_NAME)
            );
            JOOQ defaultJooqFacade = context.getBean(
                JOOQ.class,
                Qualifiers.byName("default")
            );
            assertNotNull(flowJooqFacade);
            assertSame(
                flowDataSource.unwrap(HikariUrlDataSource.class),
                flowJooqFacade.createDSLContext().getDataSource()
            );
            assertSame(
                defaultDataSource.unwrap(HikariUrlDataSource.class),
                defaultJooqFacade.createDSLContext().getDataSource()
            );
            assertNotSame(flowDataSource, defaultDataSource);
        }
    }

    /**
     * 宿主增加多个无限定名配置时，Flow 仍按名称解析配置。
     * @throws SQLException 连接池 unwrap 失败时抛出
     */
    @Test
    void createsNamedJooqBeansWhenHostAddsUnqualifiedConfigurations()
        throws SQLException {
        Map<String, Object> properties = Map.ofEntries(
            Map.entry(
                "datasources.flow.url",
                "jdbc:postgresql://127.0.0.1:1/flow"
            ),
            Map.entry("datasources.flow.username", "flow"),
            Map.entry("datasources.flow.password", "flow"),
            Map.entry(
                "datasources.flow.driver-class-name",
                "org.postgresql.Driver"
            ),
            Map.entry("datasources.flow.initialization-fail-timeout", -1),
            Map.entry("jooq.datasources.flow.sql-dialect", "POSTGRES"),
            Map.entry("flow.test.unqualified-jooq-configurations", true),
            Map.entry("micronaut.config-client.enabled", false),
            Map.entry("consul.client.registration.enabled", false),
            Map.entry("consul.client.config.enabled", false),
            Map.entry("grpc.server.enabled", false),
            Map.entry("thrift.server.enabled", false),
            Map.entry("pulsar.consumer.enabled", false)
        );

        try (var engine = new FlowDataSourceEngineTestSupport();
             ApplicationContext context = startContext(properties, engine, true)) {
            assertNotNull(context.getBean(
                JOOQ.class,
                Qualifiers.byName(FlowDatabase.DATA_SOURCE_NAME)
            ));
        }
    }

    /** 已声明但无效的 Flow JDBC URL 必须启动失败，不能当作未配置或使用默认库。 */
    @Test
    void rejectsAnExplicitlyInvalidFlowDataSource() {
        var properties = Map.<String, Object>ofEntries(
            Map.entry("datasources.flow.url", "jdbc:unsupported:flow"),
            Map.entry("datasources.flow.driver-class-name", "org.postgresql.Driver"),
            Map.entry("datasources.flow.username", "flow"),
            Map.entry("datasources.flow.password", "flow"),
            Map.entry("jooq.datasources.flow.sql-dialect", "POSTGRES"),
            Map.entry("micronaut.config-client.enabled", false),
            Map.entry("consul.client.registration.enabled", false),
            Map.entry("consul.client.config.enabled", false),
            Map.entry("grpc.server.enabled", false),
            Map.entry("thrift.server.enabled", false),
            Map.entry("pulsar.consumer.enabled", false));
        var failure = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> {
            try (var engine = new FlowDataSourceEngineTestSupport();
                 var context = startContext(properties, engine, false)) {
                context.getBean(JOOQ.class, Qualifiers.byName("flow"));
            }
        });
        var messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        org.junit.jupiter.api.Assertions.assertTrue(messages.toString().contains("jdbc:unsupported:flow"),
            "失败必须来自无效 Flow URL，不能由无关的外部 Engine 装配错误满足断言");
    }

    /**
     * 保留真实具名数据源装配，复用未启动外部插件的原生 Engine 测试边界。
     * @param properties 此场景的独立配置
     * @param engine 此场景拥有的真实 CRUD Engine
     * @param hostConfigurations 是否包含宿主无限定名配置
     * @return 已启动且由调用方关闭的应用上下文
     */
    private static ApplicationContext startContext(Map<String, Object> properties,
            FlowDataSourceEngine engine, boolean hostConfigurations) {
        var builder = ApplicationContext.builder().properties(properties);
        if (hostConfigurations) {
            builder.packages(FlowNamedDataSourceTest.class.getPackageName()).exclude(DefaultExecutor.class.getName());
        }
        var context = builder.build();
        context.registerSingleton(FlowDataSourceEngine.class, engine, null, false);
        try {
            return context.start();
        } catch (RuntimeException failure) {
            context.close();
            throw failure;
        }
    }

    @Factory
    @Requires(
        property = "flow.test.unqualified-jooq-configurations",
        value = "true"
    )
    static class HostJooqConfigurations {

        /** @return 宿主第一份无限定名配置 */
        @Singleton
        org.jooq.Configuration first() {
            return new DefaultConfiguration();
        }

        /** @return 宿主第二份无限定名配置 */
        @Singleton
        org.jooq.Configuration second() {
            return new DefaultConfiguration();
        }
    }
}
