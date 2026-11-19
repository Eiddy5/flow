package org.flow.gen.flow;

import io.micronaut.json.JsonMapper;

import org.flow.gen.flow.enums.LogLevel;
import org.flow.gen.flow.enums.StateType;
import org.flow.gen.flow.pojos.ExecutionOutputsObject;
import org.flow.gen.flow.pojos.FlowsObject;
import org.flow.gen.flow.pojos.QueuesObject;
import org.flow.gen.flow.pojos.TaskOutputsObject;
import org.flow.gen.flow.records.ExecutionsRecord;
import org.flow.gen.flow.records.LogsRecord;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.TableRecord;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.paas.json.JsonFactory;
import org.paas.json.JsonObject;
import org.x9.jooq.common.JooqPojo;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedObjectsTest {
    @Test
    void exposesAllRuntimeTablesWithoutRetiredProductTables() {
        Set<String> expected = Set.of(
            "queues", "flows", "executions", "triggers", "logs", "multipleconditions", "executordelayed",
            "settings", "service_instance", "worker_job_running", "locks", "task_outputs",
            "execution_outputs"
        );
        assertEquals(expected, runtimeTables().map(Table::getName).collect(Collectors.toSet()));
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("runtimeTables")
    void generatedTablesRecordsAndPojosShareTheInternalContract(Table<?> table) throws Exception {
        Class<?> recordType = table.getRecordType();
        String pojoName = "org.flow.gen.flow.pojos."
            + recordType.getSimpleName().replaceFirst("Record$", "Object");
        var pojo = assertInstanceOf(JooqPojo.class, Class.forName(pojoName).getConstructor().newInstance());
        var record = assertInstanceOf(TableRecord.class, pojo.toRecord());

        assertTrue(table.getClass().getSimpleName().endsWith("Table"));
        assertSame(table, pojo.table());
        assertEquals(recordType, record.getClass());
        assertSame(table, record.getTable());
        assertNotNull(table.getPrimaryKey());
        assertArrayEquals(table.getPrimaryKey().getFields().stream().map(Field::getName).toArray(String[]::new),
            pojo.primaryKeyNames());
        assertEquals(pojo.primaryKeyNames().length, pojo.primaryKeyValues().length);
        assertEquals(Arrays.stream(table.fields()).map(Field::getName).toList(),
            pojo.toMap().keySet().stream().toList());

        var roundTrip = assertInstanceOf(JooqPojo.class, recordType.getMethod("asObject").invoke(record));
        assertEquals(pojo.getClass(), roundTrip.getClass());
        assertEquals(pojo.toMap(), roundTrip.toMap());
    }

    @Test
    void flowJsonbAndDatabaseColumnNamesSurviveRecordAndPojoConversions() {
        var flow = new FlowsObject();
        flow.key = "tenant/demo/flow/3";
        flow.id = "flow";
        flow.namespace = "demo";
        flow.revision = 3;
        flow.sourceCode = "id: flow\nnamespace: demo\n";
        flow.tenantId = "tenant";
        flow.value = JSONB.valueOf("{\"id\":\"flow\",\"nested\":{\"enabled\":true},\"items\":[1,2]}");

        var record = flow.toRecord();
        var restored = record.asObject();
        assertEquals(flow.key, record.getKey());
        assertEquals(flow.value, record.getValue());
        assertEquals(flow.value, restored.value);
        assertEquals(flow.toMap(), restored.toMap());
        assertArrayEquals(new Object[]{flow.key}, flow.primaryKeyValues());
        assertNull(flow.getIdIfExists(), "The database primary key is key; domain id is not its substitute.");
        assertEquals(flow.value, flow.toMap().get(Tables.FLOWS.VALUE.getName()));
        assertEquals(flow.sourceCode, flow.toMap().get(Tables.FLOWS.SOURCE_CODE.getName()));
        assertEquals(JSONB.class, Tables.FLOWS.VALUE.getType());
    }

    @Test
    void inheritedPojoExtrasUseCompatibleMicronautAndPaasJsonMethods() {
        var previousMapper = JsonFactory.instance;
        try {
            // Plain unit tests establish the JsonFactory lifecycle normally supplied by Micronaut.
            var mapper = JsonMapper.createDefault();
            new JsonFactory(mapper).init();
            assertSame(mapper, JsonFactory.instance);

            var flow = new FlowsObject();
            flow.key = "flow-key";
            var metadata = JsonObject.Create("origin", "generated-contract-test");

            assertSame(flow, flow.put("label", "", "fallback"));
            flow.put("attempt", 3);
            flow.put("enabled", true);
            flow.put("metadata", metadata);

            assertEquals("fallback", flow.get().getString("label"));
            assertEquals(3, flow.get().getInt("attempt"));
            assertTrue(flow.get().getBoolean("enabled"));
            assertEquals("generated-contract-test", flow.get().getObject("metadata").getString("origin"));
            assertFalse(flow.toMap().containsKey("label"), "Extra JSON properties are not database columns.");
            assertEquals("flow-key", flow.toRecord().getKey());
        } finally {
            JsonFactory.instance = previousMapper;
        }
    }

    @Test
    void queuePreservesInt4OffsetAndTimestampWithTimeZone() {
        var queue = new QueuesObject();
        queue.offset = Integer.MAX_VALUE;
        queue.type = "worker-job";
        queue.key = "job";
        queue.value = JSONB.valueOf("{\"attempt\":1}");
        queue.created = OffsetDateTime.parse("2026-10-15T12:34:56.123456+08:00");

        var record = queue.toRecord();
        var restored = record.asObject();
        assertEquals(Integer.class, Tables.QUEUES.OFFSET.getType());
        assertEquals(Types.INTEGER, Tables.QUEUES.OFFSET.getDataType().getSQLType());
        assertEquals(OffsetDateTime.class, Tables.QUEUES.CREATED.getType());
        assertEquals(queue.offset, record.getOffset());
        assertEquals(queue.created, record.getCreated());
        assertEquals(queue.toMap(), restored.toMap());
        assertEquals(queue.created.getOffset(), restored.created.getOffset());
    }

    @Test
    void outputPayloadsRemainBinaryAcrossGeneratedConversions() {
        byte[] payload = new byte[]{0, 1, 127, (byte) 128, (byte) 255};
        var task = new TaskOutputsObject();
        task.key = "task-output";
        task.value = payload;
        var execution = new ExecutionOutputsObject();
        execution.key = "execution-output";
        execution.value = payload;

        assertEquals(byte[].class, Tables.TASK_OUTPUTS.VALUE.getType());
        assertEquals(byte[].class, Tables.EXECUTION_OUTPUTS.VALUE.getType());
        assertTrue(Tables.TASK_OUTPUTS.VALUE.getDataType().isBinary());
        assertTrue(Tables.EXECUTION_OUTPUTS.VALUE.getDataType().isBinary());
        assertArrayEquals(payload, task.toRecord().getValue());
        assertArrayEquals(payload, task.toRecord().asObject().value);
        assertArrayEquals(payload, execution.toRecord().getValue());
        assertArrayEquals(payload, execution.toRecord().asObject().value);
        assertArrayEquals(payload, (byte[]) task.toMap().get(Tables.TASK_OUTPUTS.VALUE.getName()));
    }

    @Test
    void databaseEnumsRemainTypedAndKeepTheirPostgresqlLiterals() {
        var execution = new ExecutionsRecord();
        execution.setStateCurrent(StateType.SUCCESS);
        var log = new LogsRecord();
        log.setLevel(LogLevel.WARN);

        assertEquals(StateType.class, Tables.EXECUTIONS.STATE_CURRENT.getType());
        assertEquals(LogLevel.class, Tables.LOGS.LEVEL.getType());
        assertSame(StateType.SUCCESS, execution.asObject().stateCurrent);
        assertSame(LogLevel.WARN, log.asObject().level);
        for (StateType value : StateType.values()) {
            assertSame(value, StateType.lookupLiteral(value.getLiteral()));
            assertEquals("state_type", value.getName());
        }
        for (LogLevel value : LogLevel.values()) {
            assertSame(value, LogLevel.lookupLiteral(value.getLiteral()));
            assertEquals("log_level", value.getName());
        }
    }

    @Test
    void generatedQueriesLeaveSchemaResolutionToTheConnection() {
        assertEquals("", DefaultSchema.DEFAULT_SCHEMA.getName());
        runtimeTables().forEach(table -> assertEquals("", table.getSchema().getName()));
        assertEquals("", StateType.SUCCESS.getSchema().getName());
        assertEquals("", LogLevel.INFO.getSchema().getName());

        String sql = DSL.using(SQLDialect.POSTGRES).select(Tables.FLOWS.KEY).from(Tables.FLOWS).getSQL();
        assertTrue(sql.contains("\"flows\""));
        assertFalse(sql.contains("\"public\"."));
        assertFalse(sql.contains("\"flow\"."));
    }

    /**
     * 校验每张生成表在限定重命名后保留 schema、Record 类型和全部字段类型。
     *
     * @param table 待验证的运行表；由方法源提供，不能为 null
     */
    @ParameterizedTest(name = "qualified rename: {0}")
    @MethodSource("runtimeTables")
    void qualifiedRenamePreservesGeneratedTableMetadata(Table<?> table) {
        var renamed = ((org.jooq.impl.TableImpl<?>) table).rename(DSL.name("flow tenant", "stored " + table.getName()));

        assertEquals(table.getClass(), renamed.getClass());
        assertEquals(table.getRecordType(), renamed.getRecordType());
        assertEquals(DSL.name("flow tenant"), renamed.getSchema().getQualifiedName());
        assertEquals(DSL.name("flow tenant", "stored " + table.getName()), renamed.getQualifiedName());
        assertEquals(Arrays.stream(table.fields()).map(Field::getDataType).toList(),
            Arrays.stream(renamed.fields()).map(Field::getDataType).toList());
        assertEquals("", table.getSchema().getName());
        assertEquals("", ((org.jooq.impl.TableImpl<?>) renamed).rename(DSL.name("unqualified")).getSchema().getName());
    }

    /**
     * 校验包含空格的 schema 和物理表名在表引用及生成字段引用中均被正确限定和引用。
     */
    @Test
    void qualifiedPhysicalNamesRenderWithGeneratedFields() {
        var flows = Tables.FLOWS.rename(DSL.name("flow tenant", "flow history"));
        String sql = DSL.using(SQLDialect.POSTGRES)
            .select(flows.KEY)
            .from(flows)
            .where(flows.KEY.eq("flow-key"))
            .getSQL();

        assertSame(flows, flows.KEY.getTable());
        assertEquals("select \"flow tenant\".\"flow history\".\"key\" "
            + "from \"flow tenant\".\"flow history\" "
            + "where \"flow tenant\".\"flow history\".\"key\" = ?", sql);
    }

    /**
     * 校验限定物理表添加别名后，FROM 保留 schema，而生成字段只通过别名引用。
     */
    @Test
    void aliasesKeepPhysicalSchemaWithoutQualifyingAliasFields() {
        var flows = Tables.FLOWS.rename(DSL.name("flow tenant", "flow history")).as("current flow");
        String sql = DSL.using(SQLDialect.POSTGRES)
            .select(flows.KEY)
            .from(flows)
            .where(flows.KEY.eq("flow-key"))
            .getSQL();

        assertNull(flows.getSchema());
        assertSame(flows, flows.KEY.getTable());
        assertEquals("select \"current flow\".\"key\" "
            + "from \"flow tenant\".\"flow history\" as \"current flow\" "
            + "where \"current flow\".\"key\" = ?", sql);
    }

    private static Stream<Table<?>> runtimeTables() {
        return DefaultSchema.DEFAULT_SCHEMA.getTables().stream();
    }
}
