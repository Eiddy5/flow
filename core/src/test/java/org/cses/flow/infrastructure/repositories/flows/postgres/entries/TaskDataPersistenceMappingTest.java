package org.cses.flow.infrastructure.repositories.flows.postgres.entries;

import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.expressions.Express;
import org.cses.flow.core.domains.expressions.TemplateExpression;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.inputs.IntegerInput;
import org.cses.flow.core.domains.flows.inputs.StringInput;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.tasks.TaskRoute;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.cses.flow.extensions.log.Log;
import org.cses.flow.extensions.flow.LoopUntil;
import org.cses.flow.extensions.flow.Parallel;
import org.cses.flow.extensions.flow.Pause;
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

    private final Context plugins = builtInContext();

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
                "处理结果：{{ dependOnOutputs.prepare.result }}"
            ))
            .dependOn(List.of("prepare"))
            .build();

        FlowTaskEntry entry = FlowTaskEntry.fromDomain(
            "company-1",
            "flow-1",
            1,
            task,
            null,
            0,
            plugins.jacksonMapper()
        );

        assertEquals(
            "处理结果：{{ dependOnOutputs.prepare.result }}",
            entry.properties.asMap().get("message")
        );
        Log restored = assertInstanceOf(
            Log.class,
            entry.toDomain(plugins.jacksonMapper(), List.of())
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

        FlowTaskEntry entry = FlowTaskEntry.fromDomain(
            "company-1",
            "flow-1",
            1,
            task,
            null,
            0,
            plugins.jacksonMapper()
        );

        assertEquals(4, entry.properties.asMap().get("concurrent"));
        Parallel restored = assertInstanceOf(
            Parallel.class,
            entry.toDomain(plugins.jacksonMapper(), List.of())
        );
        assertEquals(task, restored);
        assertEquals(4, restored.concurrent().orElseThrow());
    }

    @Test
    void flowTaskEntryRoundTripsLoopUntilExpressAsAString() {
        Task check = AutomaticTask.builder()
            .id("check-id")
            .key("check")
            .outputs(List.of(Output.create("status", DataType.STRING)))
            .build();
        LoopUntil task = LoopUntil.builder()
            .id("loop-id")
            .key("poll")
            .condition(Express.parse(
                "outputs.check.status == \"DONE\""
            ))
            .maxIterations(3)
            .tasks(List.of(check))
            .build();
        plugins.modelValidator().validate(task);

        FlowTaskEntry entry = FlowTaskEntry.fromDomain(
            "company-1",
            "flow-1",
            1,
            task,
            null,
            0,
            plugins.jacksonMapper()
        );

        assertEquals(
            "outputs.check.status == \"DONE\"",
            entry.properties.asMap().get("condition")
        );
        LoopUntil restored = assertInstanceOf(
            LoopUntil.class,
            entry.toDomain(plugins.jacksonMapper(), List.of(check))
        );
        assertEquals(task, restored);
        assertEquals(task.condition(), restored.condition());
    }

    @Test
    void flowTaskEntryRoundTripsPauseSpecificDefinitionTree() {
        Task action = AutomaticTask.builder()
            .id("action-id")
            .key("create-approval")
            .build();
        Pause task = Pause.builder()
            .id("pause-id")
            .key("wait-approval")
            .pause(action)
            .resume(List.of(StringInput.builder()
                .key("decision")
                .displayName("Decision")
                .required(true)
                .build()))
            .duration("P1M")
            .behavior(Pause.Behavior.WARN)
            .build();
        plugins.modelValidator().validate(task);

        FlowTaskEntry entry = FlowTaskEntry.fromDomain(
            "company-1",
            "flow-1",
            1,
            task,
            null,
            0,
            plugins.jacksonMapper()
        );

        assertTrue(entry.properties.asMap().containsKey("pause"));
        assertEquals("P1M", entry.properties.asMap().get("duration"));
        Pause restored = assertInstanceOf(
            Pause.class,
            entry.toDomain(plugins.jacksonMapper(), List.of())
        );
        assertEquals(task, restored);
        assertEquals(action, restored.pause());
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
        Task task = AutomaticTask.builder()
            .id("task-id")
            .key("approval")
            .inputs(List.of(definitionInput))
            .outputs(List.of(Output.create(
                "decision",
                DataType.STRING
            )))
            .route(TaskRoute.parse(
                "outputs.decision == \"approved\""
            ))
            .dependOn(List.of("prepare"))
            .build();

        FlowTaskEntry entry = FlowTaskEntry.fromDomain(
            "company-1",
            "flow-1",
            1,
            task,
            null,
            0,
            plugins.jacksonMapper()
        );

        Task restored = entry.toDomain(plugins.jacksonMapper(), List.of());
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
        entry.type = AutomaticTask.class.getName();
        entry.route = "DIRECT";
        entry.inputs = JsonObjects.FromList(List.of(Map.of(
            "key", "request",
            "type", "STRING"
        )));
        entry.outputs = JsonObjects.Create();
        entry.dependOn = JsonObjects.Create();

        Input<?> restored = entry.toDomain(
            plugins.jacksonMapper(),
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
        entry.type = AutomaticTask.class.getName();
        entry.route = "DIRECT";
        entry.inputs = JsonObjects.FromList(List.of("legacy"));
        entry.outputs = JsonObjects.Create();
        entry.dependOn = JsonObjects.Create();

        assertThrows(
            RuntimeException.class,
            () -> entry.toDomain(plugins.jacksonMapper(), List.of())
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
