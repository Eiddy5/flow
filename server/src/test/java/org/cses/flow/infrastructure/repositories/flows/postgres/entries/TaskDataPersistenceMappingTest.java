package org.cses.flow.infrastructure.repositories.flows.postgres.entries;

import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.inputs.IntegerInput;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.tasks.RouteExpression;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.TaskTypeDispatcher;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;
import org.paas.json.JsonObject;
import org.paas.json.JsonObjects;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.cses.flow.core.plugins.TaskExtensionTestSupport.builtInDispatcher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskDataPersistenceMappingTest {

    private final TaskTypeDispatcher dispatcher = builtInDispatcher();

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
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
        Task task = AutomaticTask.create(
            "task-id",
            null,
            "approval",
            List.of(definitionInput),
            List.of(Output.create("decision", DataType.STRING)),
            RouteExpression.parse(
                "outputs.decision == \"approved\""
            ),
            List.of("prepare"),
            List.of()
        );

        FlowTaskEntry entry = FlowTaskEntry.fromDomain(
            "company-1",
            "flow-1",
            1,
            task,
            0,
            dispatcher
        );

        Task restored = entry.toDomain(dispatcher, List.of());
        assertEquals(task, restored);
        IntegerInput input = (IntegerInput) restored.inputs().getFirst();
        assertEquals(2, input.getDefaultValue());
        assertEquals(1, input.getMin());
        assertEquals(5, input.getMax());
        assertEquals(List.of("prepare"), entry.dependOn.asStrings());
        assertEquals(
            "outputs.decision == \"approved\"",
            entry.route
        );
    }

    @Test
    void persistedKeyAndTypeInputGetsConservativeCommonDefaults() {
        FlowTaskEntry entry = new FlowTaskEntry();
        entry.id = "task-id";
        entry.key = "automatic";
        entry.type = "AUTO";
        entry.route = "DIRECT";
        entry.inputs = JsonObjects.FromList(List.of(Map.of(
            "key", "request",
            "type", "STRING"
        )));
        entry.outputs = JsonObjects.Create();
        entry.dependOn = JsonObjects.Create();

        Input<?> restored = entry.toDomain(
            dispatcher,
            List.of()
        ).inputs().getFirst();
        assertEquals("request", restored.getDisplayName());
        assertEquals(false, restored.isRequired());
    }

    @Test
    void persistedLegacyStringDataIsNotGivenAnInferredType() {
        FlowTaskEntry entry = new FlowTaskEntry();
        entry.id = "task-id";
        entry.key = "approval";
        entry.type = "AUTO";
        entry.route = "DIRECT";
        entry.inputs = JsonObjects.FromList(List.of("legacy"));
        entry.outputs = JsonObjects.Create();
        entry.dependOn = JsonObjects.Create();

        assertThrows(
            RuntimeException.class,
            () -> entry.toDomain(dispatcher, List.of())
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
}
