package org.cses.flow.infrastructure.repositories.flows.entries;

import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.conditions.Condition;
import org.cses.flow.core.domains.expressions.TemplateExpression;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.inputs.IntegerInput;
import org.cses.flow.core.domains.flows.inputs.StringInput;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.cses.flow.core.plugins.TestNotificationTask;
import org.cses.flow.extensions.log.Log;
import org.cses.flow.extensions.flow.LoopUntil;
import org.cses.flow.extensions.flow.Parallel;
import org.cses.flow.extensions.flow.Pause;
import org.cses.flow.infrastructure.repositories.flows.codec.DataJsonCodec;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;
import org.paas.json.JsonObject;
import org.paas.json.JsonObjects;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskDataPersistenceMappingTest {

    private Context plugins = builtInContext(new TestNotificationTask());

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    @Test
    void flowTaskEntryRoundTripsLogMessageExpression() {
        Log task = Log.builder()
            .id("log-id")
            .key("write-log")
            .message(TemplateExpression.parse(
                "处理结果：{{ outputs.prepare.result }}"
            ))
            .build();

        FlowTaskEntry entry = FlowTaskEntry.from(
            "company-1",
            "flow-1",
            1,
            task,
            null,
            0
        );

        assertEquals(
            "处理结果：{{ outputs.prepare.result }}",
            properties(entry).asMap().get("message")
        );
        Log restored = assertInstanceOf(
            Log.class,
            entry.to(List.of())
        );
        assertEquals(task, restored);
        assertEquals(task.message(), restored.message());
    }

    @Test
    void flowTaskEntryRoundTripsParallelConcurrentProperty() {
        Parallel task = Parallel.builder()
            .id("parallel-id")
            .key("parallel")
            .concurrent(4)
            .build();

        FlowTaskEntry entry = FlowTaskEntry.from(
            "company-1",
            "flow-1",
            1,
            task,
            null,
            0
        );

        assertEquals(4, properties(entry).asMap().get("concurrent"));
        Parallel restored = assertInstanceOf(
            Parallel.class,
            entry.to(List.of())
        );
        assertEquals(task, restored);
        assertEquals(4, restored.concurrent().orElseThrow());
    }

    @Test
    void flowTaskEntryRoundTripsARegisteredPluginSpecificProperty() {
        TestNotificationTask task = TestNotificationTask.builder()
            .id("notification-id")
            .key("notify")
            .channel("operations")
            .build();

        FlowTaskEntry entry = FlowTaskEntry.from(
            "company-1",
            "flow-1",
            1,
            task,
            null,
            0
        );

        assertEquals("operations", properties(entry).getString("channel"));
        TestNotificationTask restored = assertInstanceOf(
            TestNotificationTask.class,
            entry.to(List.of())
        );
        assertEquals(task, restored);
        assertEquals("operations", restored.channel());
    }

    @Test
    void flowTaskEntryRoundTripsLoopUntilConditionAsAString() {
        Task check = org.cses.flow.core.plugins.TestOutputTasks.Status.builder()
            .id("check-id")
            .key("check")

            .build();
        LoopUntil task = LoopUntil.builder()
            .id("loop-id")
            .key("poll")
            .condition(Condition.parser(
                "{{ outputs.check.status }} == DONE"
            ))
            .maxIterations(3)
            .tasks(List.of(check))
            .build();
        plugins.modelValidator().validate(task);

        FlowTaskEntry entry = FlowTaskEntry.from(
            "company-1",
            "flow-1",
            1,
            task,
            null,
            0
        );

        assertEquals(
            "{{ outputs.check.status }} == DONE",
            properties(entry).asMap().get("condition")
        );
        LoopUntil restored = assertInstanceOf(
            LoopUntil.class,
            entry.to(List.of(check))
        );
        assertEquals(task, restored);
        assertEquals(task.condition(), restored.condition());
    }

    @Test
    void flowTaskEntryRoundTripsPauseSpecificDefinitionTree() {
        Task action = Log.builder()
            .id("action-id")
            .key("create-approval")
            .message(TemplateExpression.parse("test step"))
            .build();
        Pause task = Pause.builder()
            .id("pause-id")
            .key("wait-approval")
            .onPause(action)
            .onResume(List.of(StringInput.builder()
                .key("decision")
                .displayName("Decision")
                .required(true)
                .build()))

            .duration("P1M")
            .behavior(Pause.Behavior.WARN)
            .build();
        plugins.modelValidator().validate(task);

        FlowTaskEntry entry = FlowTaskEntry.from(
            "company-1",
            "flow-1",
            1,
            task,
            null,
            0
        );

        assertTrue(properties(entry).asMap().containsKey("onPause"));
        assertEquals("P1M", properties(entry).asMap().get("duration"));
        Pause restored = assertInstanceOf(
            Pause.class,
            entry.to(List.of())
        );
        assertEquals(task, restored);
        assertEquals(action, restored.onPause());
        assertEquals(List.of(action), restored.definitionChildren());
        assertEquals(
            List.of(Output.create("decision", DataType.STRING)),
            restored.outputs()
        );
    }

    @Test
    void flowTaskEntryRoundTripsExplicitDefinitionFields() {
        Input<?> definitionInput = JsonObject.FromMap(
            Map.of(
                "key", "attempts",
                "type", "INTEGER",
                "displayName", "尝试次数",
                "required", true,
                "defaultValue", 2,
                "min", 1,
                "max", 5
            )
        ).asObject(Input.class);
        Task task = Log.builder()
            .id("task-id")
            .key("approval")
            .message(TemplateExpression.parse("test step"))
            .inputs(List.of(definitionInput))

            .build();

        FlowTaskEntry entry = FlowTaskEntry.from(
            "company-1",
            "flow-1",
            1,
            task,
            null,
            0
        );

        Task restored = entry.to(List.of());
        assertEquals(task, restored);
        IntegerInput input = (IntegerInput) restored.inputs().getFirst();
        assertEquals(2, input.getDefaultValue());
        assertEquals(1, input.getMin());
        assertEquals(5, input.getMax());
        assertEquals(0, entry.position);
        assertEquals("approval", entry.displayName);
    }

    @Test
    void persistedKeyAndTypeInputGetsConservativeCommonDefaults() {
        FlowTaskEntry entry = new FlowTaskEntry();
        entry.id = "task-id";
        entry.key = "automatic";
        entry.displayName = "automatic";
        entry.type = Log.class.getName();
        entry.inputs = JSONB.valueOf(JsonObjects.FromList(List.of(Map.of(
            "key", "request",
            "type", "STRING"
        ))).toJson());
        entry.outputs = JSONB.valueOf(JsonObjects.Create().toJson());

        Input<?> restored = entry.to(List.of()).inputs().getFirst();
        assertEquals("request", restored.getDisplayName());
        assertEquals(false, restored.isRequired());
    }

    @Test
    void persistedScalarDataIsRejectedInsteadOfReceivingAnInferredType() {
        FlowTaskEntry entry = new FlowTaskEntry();
        entry.id = "task-id";
        entry.key = "approval";
        entry.displayName = "approval";
        entry.type = Log.class.getName();
        entry.inputs = JSONB.valueOf(JsonObjects.FromList(List.of("legacy")).toJson());
        entry.outputs = JSONB.valueOf(JsonObjects.Create().toJson());

        assertThrows(
            RuntimeException.class,
            () -> entry.to(List.of())
        );
    }

    @Test
    void inputCodecRoundTripsEveryJsonSubtype() {
        Map<DataType, Object> defaults = Map.of(
            DataType.STRING, "text",
            DataType.BOOLEAN, true,
            DataType.BYTE, (byte) 1,
            DataType.SHORT, (short) 2,
            DataType.INTEGER, 3,
            DataType.LONG, 4L,
            DataType.FLOAT, 1.5F,
            DataType.DOUBLE, 2.5D,
            DataType.CHARACTER, 'A'
        );
        List<Input<?>> inputs = new ArrayList<>();
        for (DataType type : DataType.values()) {
            Input<?> input = JsonObject.FromMap(Map.of(
                "key", type.name().toLowerCase(),
                "type", type.name(),
                "displayName", type.name(),
                "required", false,
                "defaultValue", defaults.get(type)
            )).asObject(Input.class);
            inputs.add(input);
        }

        assertEquals(
            inputs,
            DataJsonCodec.decodeInputs(
                DataJsonCodec.encode(inputs),
                "Flow.inputs"
            )
        );
    }

    private static JsonObject properties(FlowTaskEntry entry) {
        return JsonObject.Parse(entry.properties.data());
    }
}
