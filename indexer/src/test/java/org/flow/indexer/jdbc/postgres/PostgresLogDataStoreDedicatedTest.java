package org.flow.indexer.jdbc.postgres;

import org.flow.core.jdbc.postgres.PostgresRepository;
import org.flow.core.jdbc.postgres.PostgresSchema;
import org.flow.indexer.jdbc.LogJdbcDataSourceProvider;

import java.time.Instant;
import java.util.List;

import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import org.flow.core.models.executions.LogEntry;
import org.flow.core.repositories.LogDataStoreInterface;
import org.flow.core.repositories.log.LogDataStoreInterfaceFactory;
import org.flow.core.repositories.log.LogsConfig;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the dedicated-datasource path: with {@code flow.logs.type=postgres} + a distinct
 * {@code flow.logs.postgres.url}, the log store writes/reads the explicitly initialized dedicated Postgres database.
 */
@MicronautTest(transactional = false)
@Property(name = "flow.logs.type", value = "postgres")
@Property(name = "flow.logs.postgres.table", value = "dedicated_logs")
@Property(name = "flow.logs.postgres.url", value = "${FLOW_LOGS_TEST_URL}")
@Property(name = "flow.logs.postgres.username", value = "${FLOW_UPSTREAM_TEST_USER}")
@Property(name = "flow.logs.postgres.password", value = "${FLOW_UPSTREAM_TEST_PASSWORD}")
class PostgresLogDataStoreDedicatedTest {

    @Inject
    LogDataStoreInterfaceFactory logRepositoryInterfaceFactory;

    @Inject
    LogsConfig logsConfig;

    // The low-level repository bound to the PRIMARY datasource's `logs` table, to prove logs did NOT land there.
    @Inject
    @Named("logs")
    PostgresRepository<LogEntry> primaryLogs;

    @Inject
    LogJdbcDataSourceProvider logDataSourceProvider;

    @BeforeEach
    void initializeLogTable() throws Exception {
        PostgresSchema.initializeLogs(logDataSourceProvider.dataSource(), logDataSourceProvider.table());
    }

    @Test
    void shouldWriteAndReadFromDedicatedDatabaseAndNotThePrimary() {
        // Given: the postgres log store built against the dedicated datasource, from the real flow.logs.postgres.* config
        LogDataStoreInterface dedicated = logRepositoryInterfaceFactory.make("postgres", logsConfig.getLogConfig("postgres"));
        String executionId = "dedicated-exec-" + Instant.now().toEpochMilli();
        LogEntry log = LogEntry.builder()
            .tenantId("main")
            .namespace("org.flow.tests")
            .flowId("dedicated-flow")
            .executionId(executionId)
            .level(Level.INFO)
            .timestamp(Instant.now())
            .message("hello dedicated log store")
            .build();

        // When: a log is saved through the dedicated store
        dedicated.save(log);

        // Then: it is readable from the dedicated store...
        List<LogEntry> fromDedicated = dedicated.findByExecutionId("main", executionId, Level.TRACE);
        assertThat(fromDedicated).hasSize(1);
        assertThat(fromDedicated.getFirst().getMessage()).isEqualTo("hello dedicated log store");

        // ...and absent from the primary database.
        assertThat(primaryLogs.count(DSL.field(DSL.name("execution_id")).eq(executionId))).isZero();
    }
}
