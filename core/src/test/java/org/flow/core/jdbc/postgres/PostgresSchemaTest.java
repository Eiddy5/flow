package org.flow.core.jdbc.postgres;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.flow.gen.flow.DefaultSchema;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.micronaut.context.annotation.Property;
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import static org.assertj.core.api.Assertions.assertThat;

@MicronautTest(transactional = false, rebuildContext = true)
@Property(name = "flow.test.initialize-schema", value = "false")
@EnabledIfEnvironmentVariable(named = "FLOW_UPSTREAM_TEST_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/flow_unit")
class PostgresSchemaTest {
    private static final Set<String> EXPECTED_TABLES = Set.of(
        "queues", "flows", "executions", "triggers", "logs", "multipleconditions", "executordelayed",
        "settings", "service_instance", "worker_job_running", "locks", "task_outputs", "execution_outputs"
    );

    @Inject
    PostgresSchema schema;

    @Inject
    @Named("flow")
    DataSource dataSource;

    @Test
    @Property(name = "datasources.flow.schema", value = "test_fresh_${random.uuid}")
    void initializesExactlyTheRuntimeTablesAndPreservesDataOnRepeat() throws Exception {
        DSLContext sql = DSL.using(DelegatingDataSource.unwrapDataSource(dataSource), SQLDialect.POSTGRES);
        assertThat(tables(sql)).isEmpty();

        schema.initialize();

        assertThat(tables(sql)).isEqualTo(EXPECTED_TABLES);
        assertThat(DefaultSchema.DEFAULT_SCHEMA.getTables()).extracting(Table::getName)
            .containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
        sql.execute("INSERT INTO settings (key, value) VALUES ('existing', '{\"value\":true}')");

        schema.initialize();

        assertThat(tables(sql)).isEqualTo(EXPECTED_TABLES);
        assertThat(sql.fetchSingle("SELECT value->>'value' FROM settings WHERE key = 'existing'").get(0, String.class)).isEqualTo("true");
    }

    @Test
    @Property(name = "datasources.flow.schema", value = "test_schema_locks_${random.uuid}")
    void createsLockColumnsWithTheRuntimeWidthAndExpiry() throws Exception {
        schema.initialize();
        DSLContext sql = DSL.using(DelegatingDataSource.unwrapDataSource(dataSource), SQLDialect.POSTGRES);
        String longId = "a".repeat(480);
        sql.execute("""
            INSERT INTO locks (key, value) VALUES
                (?, jsonb_build_object('category', 'lease', 'id', ?, 'owner', 'worker', 'lockedUntil', '2026-01-01T00:00:00.123456Z')),
                ('mutex', '{"category":"mutex","id":"controller","owner":"scheduler"}')
            """, longId, longId);

        var lease = sql.fetchSingle("SELECT id, locked_until FROM locks WHERE key = ?", longId);
        assertThat(lease.get("id", String.class)).isEqualTo(longId);
        assertThat(lease.get("locked_until", OffsetDateTime.class)).isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00.123456Z"));
        assertThat(sql.fetchSingle("SELECT locked_until FROM locks WHERE key = 'mutex'").get(0)).isNull();
    }

    private Set<String> tables(DSLContext sql) {
        return sql.fetch("SELECT tablename FROM pg_tables WHERE schemaname = current_schema()")
            .stream().map(row -> row.get(0, String.class)).collect(Collectors.toSet());
    }
}
