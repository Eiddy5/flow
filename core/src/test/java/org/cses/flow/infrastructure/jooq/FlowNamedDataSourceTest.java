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

final class FlowNamedDataSourceTest {

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

        try (ApplicationContext context = ApplicationContext.run(properties)) {
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

        try (ApplicationContext context = ApplicationContext.run(properties)) {
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

        try (ApplicationContext context = ApplicationContext.builder()
            .packages(FlowNamedDataSourceTest.class.getPackageName())
            .exclude(DefaultExecutor.class.getName())
            .properties(properties)
            .start()) {
            assertNotNull(context.getBean(
                JOOQ.class,
                Qualifiers.byName(FlowDatabase.DATA_SOURCE_NAME)
            ));
        }
    }

    @Factory
    @Requires(
        property = "flow.test.unqualified-jooq-configurations",
        value = "true"
    )
    static final class HostJooqConfigurations {

        @Singleton
        org.jooq.Configuration first() {
            return new DefaultConfiguration();
        }

        @Singleton
        org.jooq.Configuration second() {
            return new DefaultConfiguration();
        }
    }
}
