package org.flow.core.jdbc;

import org.flow.jdbc.JdbcTableConfig;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.RecordMapper;
import org.jooq.SQLDialect;
import org.jooq.SelectConditionStep;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.LogEntry;
import org.flow.core.models.executions.TaskOutput;
import org.flow.core.repositories.ArrayListTotal;
import org.flow.gen.flow.Tables;
import org.flow.gen.flow.enums.LogLevel;
import org.flow.gen.flow.enums.StateType;
import org.flow.gen.flow.tables.ExecutionsTable;
import org.flow.gen.flow.tables.LogsTable;

import io.micronaut.data.model.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcGeneratedMetadataTest {
    /** 验证默认绑定直接持有 gen 表及其字段实例。 */
    @Test
    void defaultRelationIsTheGeneratedTableInstance() {
        var config = new JdbcTableConfig("executions", Execution.class, Tables.EXECUTIONS);
        var repository = new TestJdbcRepository<Execution>(config);
        var table = repository.getTable(Tables.EXECUTIONS);

        assertThat(config.jooqTable()).isSameAs(Tables.EXECUTIONS);
        assertThat(repository.getTable()).isSameAs(Tables.EXECUTIONS);
        assertThat(table).isSameAs(Tables.EXECUTIONS).isInstanceOf(ExecutionsTable.class);
        assertThat(table.getQualifiedName().getName()).containsExactly("executions");
        assertThat(repository.keyField()).isSameAs(table.KEY);
        assertThat(repository.valueField()).isSameAs(table.VALUE);
    }

    /** 验证配置 schema 与物理表名后仍保留生成表类型和字段归属。 */
    @Test
    void configuredSchemaAndPhysicalNameRetainTheGeneratedTableType() {
        var config = new JdbcTableConfig("executions", Execution.class, "\"tenant schema\".\"execution rows\"", Tables.EXECUTIONS);
        var repository = new TestJdbcRepository<Execution>(config);
        var table = repository.getTable(Tables.EXECUTIONS);

        assertThat(table).isInstanceOf(ExecutionsTable.class).isNotSameAs(Tables.EXECUTIONS);
        assertThat(table.getQualifiedName().getName()).containsExactly("tenant schema", "execution rows");
        assertThat(table.getSchema().getName()).isEqualTo("tenant schema");
        assertThat(table.START_DATE.getTable()).isSameAs(table);
        assertThat(table.VALUE.getTable()).isSameAs(table);
        assertThat(repository.valueField()).isSameAs(table.VALUE);
        assertThat(DSL.using(SQLDialect.POSTGRES).render(table)).isEqualTo("\"tenant schema\".\"execution rows\"");
    }

    /** 验证独立日志表改名后保留生成的枚举和 JSONB 字段。 */
    @Test
    void independentLogStoreUsesItsGeneratedTableWithTheConfiguredName() {
        var config = new JdbcTableConfig("logs", LogEntry.class, "audit.flow_logs", Tables.LOGS);
        var repository = new TestJdbcRepository<LogEntry>(config);
        var table = repository.getTable(Tables.LOGS);

        assertThat(config.table()).isEqualTo("audit.flow_logs");
        assertThat(table).isInstanceOf(LogsTable.class);
        assertThat(table.getQualifiedName().getName()).containsExactly("audit", "flow_logs");
        assertThat(table.getSchema().getName()).isEqualTo("audit");
        assertThat(table.LEVEL.getTable()).isSameAs(table);
        assertThat(table.LEVEL.getType()).isEqualTo(LogLevel.class);
        assertThat(repository.valueField()).isSameAs(table.VALUE);
    }

    /** 验证缺少生成表或访问不匹配的生成表类型时明确拒绝。 */
    @Test
    void unregisteredTablesAndMismatchedGeneratedTypesAreRejected() {
        assertThatThrownBy(() -> new JdbcTableConfig("external", Object.class, "extension.custom_table", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("external");

        var repository = new TestJdbcRepository<Execution>(new JdbcTableConfig("executions", Execution.class, Tables.EXECUTIONS));
        assertThatThrownBy(() -> repository.getTable(Tables.LOGS))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** 验证各类存储绑定保留 gen 中的数据库字段类型。 */
    @Test
    void generatedFieldsRetainTheirDatabaseTypes() {
        var executions = new TestJdbcRepository<>(new JdbcTableConfig("executions", Execution.class, Tables.EXECUTIONS)).getTable(Tables.EXECUTIONS);
        var logs = new TestJdbcRepository<>(new JdbcTableConfig("logs", LogEntry.class, Tables.LOGS)).getTable(Tables.LOGS);
        var queues = new TestJdbcRepository<>(new JdbcTableConfig("queues", Object.class, Tables.QUEUES)).getTable(Tables.QUEUES);
        var outputs = new TestJdbcRepository<>(new JdbcTableConfig("taskoutputs", TaskOutput.class, Tables.TASK_OUTPUTS)).getTable(Tables.TASK_OUTPUTS);

        assertThat(executions.STATE_CURRENT.getType()).isEqualTo(StateType.class);
        assertThat(executions.VALUE.getType()).isEqualTo(JSONB.class);
        assertThat(executions.START_DATE.getType()).isEqualTo(LocalDateTime.class);
        assertThat(logs.LEVEL.getType()).isEqualTo(LogLevel.class);
        assertThat(logs.TIMESTAMP.getType()).isEqualTo(OffsetDateTime.class);
        assertThat(queues.CREATED.getType()).isEqualTo(OffsetDateTime.class);
        assertThat(queues.OFFSET.getType()).isEqualTo(Integer.class);
        assertThat(outputs.VALUE.getType()).isEqualTo(byte[].class);
    }

    /** 验证生成记录正确承载 JSONB、枚举、二进制和时间值。 */
    @Test
    void generatedRecordsReadJsonEnumsBinaryValuesAndTimestampValues() {
        var context = DSL.using(SQLDialect.POSTGRES);
        var executionTable = new TestJdbcRepository<>(new JdbcTableConfig("executions", Execution.class, Tables.EXECUTIONS)).getTable(Tables.EXECUTIONS);
        var execution = context.newRecord(executionTable);
        execution.set(executionTable.VALUE, JSONB.valueOf("{\"id\":\"execution-id\"}"));
        execution.set(executionTable.STATE_CURRENT, StateType.SUCCESS);

        assertThat(execution.get(executionTable.VALUE).data()).isEqualTo("{\"id\":\"execution-id\"}");
        assertThat(execution.get(executionTable.STATE_CURRENT)).isSameAs(StateType.SUCCESS);

        var outputTable = new TestJdbcRepository<>(new JdbcTableConfig("taskoutputs", TaskOutput.class, Tables.TASK_OUTPUTS)).getTable(Tables.TASK_OUTPUTS);
        var output = context.newRecord(outputTable);
        byte[] bytes = new byte[] {0, 1, -1, 127};
        output.set(outputTable.VALUE, bytes);
        assertThat(output.get(outputTable.VALUE)).containsExactly(bytes);

        var queueTable = new TestJdbcRepository<>(new JdbcTableConfig("queues", Object.class, Tables.QUEUES)).getTable(Tables.QUEUES);
        var queued = context.newRecord(queueTable);
        OffsetDateTime created = OffsetDateTime.parse("2026-10-16T08:30:00.123456+08:00");
        queued.set(queueTable.CREATED, created);
        assertThat(queued.get(queueTable.CREATED)).isEqualTo(created);
    }

    /** 验证持久化仅写入 JSONB 载荷，将生成列交给数据库计算。 */
    @Test
    void nativePersistenceWritesOnlyTheJsonbPayload() {
        var repository = new TestJdbcRepository<Map<String, String>>(
            new JdbcTableConfig("executions", Map.class, Tables.EXECUTIONS));
        var table = repository.getTable(Tables.EXECUTIONS);
        var persistedFields = repository.persistFields(Map.of("id", "execution-id"));

        assertThat(persistedFields).containsOnlyKeys(table.VALUE);
        assertThat(persistedFields.keySet().iterator().next()).isSameAs(table.VALUE);
        assertThat(persistedFields.get(table.VALUE)).isInstanceOf(JSONB.class);
        assertThat(((JSONB) persistedFields.get(table.VALUE)).data()).isEqualTo("{\"id\":\"execution-id\"}");
    }

    private static class TestJdbcRepository<T> extends AbstractJdbcRepository<T> {
        private TestJdbcRepository(JdbcTableConfig config) {
            super(config, null);
        }

        @Override
        public Condition fullTextCondition(List<? extends Field<?>> fields, String query) {
            return DSL.noCondition();
        }

        @Override
        public <R extends Record, E> ArrayListTotal<E> fetchPage(DSLContext context, SelectConditionStep<R> select,
            Pageable pageable, RecordMapper<R, E> mapper) {
            return new ArrayListTotal<>(0);
        }
    }
}
