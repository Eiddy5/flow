package org.flow.indexer.jdbc;

import java.util.Map;

import org.jooq.conf.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.flow.core.contexts.configuration.RepositoryConfiguration;
import org.flow.core.exceptions.FlowRuntimeException;
import org.flow.core.repositories.log.LogsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogJdbcDataSourceProviderTest {

    private static LogJdbcDataSourceProvider provider(Map<String, Object> logs) {
        return new LogJdbcDataSourceProvider(new LogsConfig(logs), new Settings(), null,
            new RepositoryConfiguration("postgres"));
    }

    @Test
    void shouldReusePrimaryPostgresWhenNoDedicatedUrl() {
        LogJdbcDataSourceProvider provider = provider(Map.of("type", "postgres"));
        assertThat(provider.isDedicated()).isFalse();
        assertThat(provider.dedicatedWrapper()).isNull();
        assertThat(provider.table()).isEqualTo("logs");
    }

    @Test
    void shouldReusePrimaryWhenTypeIsAbsent() {
        assertThat(provider(Map.of()).isDedicated()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = { "h2", "memory", "mysql" })
    void shouldRejectRetiredDatabaseTypes(String type) {
        assertThatThrownBy(() -> provider(Map.of("type", type)).isDedicated())
            .isInstanceOf(FlowRuntimeException.class)
            .hasMessageContaining("only postgres is supported");
    }

    @Test
    void shouldFailFastWhenUrlIsConfiguredWithoutUsername() {
        LogJdbcDataSourceProvider provider = provider(Map.of("type", "postgres", "postgres", Map.of(
            "url", "jdbc:postgresql://postgres-logs:5432/flow_logs")));
        assertThatThrownBy(provider::isDedicated)
            .isInstanceOf(FlowRuntimeException.class)
            .hasMessageContaining("flow.logs.postgres.url")
            .hasMessageContaining("flow.logs.postgres.username")
            .hasMessageContaining("never inherited");
    }

    @Test
    void shouldRejectANonPostgresUrlBeforeOpeningAPool() {
        LogJdbcDataSourceProvider provider = provider(Map.of("type", "postgres", "postgres", Map.of(
            "url", "jdbc:mysql://unused-host/logs", "username", "unused")));
        assertThatThrownBy(provider::isDedicated)
            .isInstanceOf(FlowRuntimeException.class)
            .hasMessageContaining("must be a PostgreSQL JDBC URL");
    }
}
