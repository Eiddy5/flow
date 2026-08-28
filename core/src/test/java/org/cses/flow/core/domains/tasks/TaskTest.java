package org.cses.flow.core.domains.tasks;

import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.expressions.TemplateExpression;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.cses.flow.core.plugins.TestNotificationTask;
import org.cses.flow.extensions.log.Log;
import org.cses.flow.extensions.flow.Pause;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;
import org.paas.json.JsonObject;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskTest {

    private static final Context PLUGINS = builtInContext(
        new TestNotificationTask()
    );

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    @Test
    void exposesReadOnlyBoundDefinitionState() {
        Task task = PLUGINS.modelValidator().validate(
            Log.builder()
                .id("task-id")
                .key("task")
                .message(TemplateExpression.parse("test step"))
                .inputs(List.of(input("request", DataType.STRING)))
                .outputs(List.of(Output.create(
                    "result",
                    DataType.STRING
                )))
                .build()
        );

        assertEquals(Log.class.getName(), task.getType());
        assertEquals("task", task.key());
        assertTrue(task.declaresOutput("result"));
        assertTrue(task.definitionChildren().isEmpty());
        assertThrows(
            UnsupportedOperationException.class,
            () -> task.inputs().add(input("other", DataType.STRING))
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> task.outputs().add(Output.create("other", DataType.STRING))
        );
        assertFalse(Arrays.stream(Task.class.getMethods())
            .anyMatch(method -> method.getName().startsWith("set")));
    }

    @Test
    void usesDefinitionValueEqualityAcrossPersistenceBoundaries() {
        Task first = Log.builder()
            .id("task-1")
            .key("approval")
            .message(TemplateExpression.parse("test step"))
            .inputs(List.of(input("request", DataType.STRING)))
            .outputs(List.of(Output.create("decision", DataType.STRING)))
            .build();
        Task restored = Log.builder()
            .id("task-1")
            .key("approval")
            .message(TemplateExpression.parse("test step"))
            .inputs(List.of(input("request", DataType.STRING)))
            .outputs(List.of(Output.rehydrate(
                "decision",
                DataType.STRING
            )))
            .build();

        assertEquals(first, restored);
        assertEquals(first.hashCode(), restored.hashCode());
    }

    @Test
    void activeValidatorRejectsInvalidDirectlyCreatedTasks() {
        assertThrows(
            IllegalArgumentException.class,
            () -> PLUGINS.modelValidator().validate(
                Log.builder()
                    .id("task-1")
                    .key("approval")
                    .message(TemplateExpression.parse("test step"))
                    .inputs(List.of(
                        input("request", DataType.STRING),
                        input("request", DataType.INTEGER)
                    ))
                    .build()
            )
        );
        assertThrows(
            jakarta.validation.ConstraintViolationException.class,
            () -> PLUGINS.modelValidator().validate(
                Log.builder()
                    .id(" ")
                    .key("task")
                    .message(TemplateExpression.parse("test step"))
                    .build()
            )
        );
        assertThrows(
            jakarta.validation.ConstraintViolationException.class,
            () -> PLUGINS.modelValidator().validate(
                TestNotificationTask.builder()
                    .id("task-2")
                    .key("notify")
                    .build()
            )
        );
    }

    @Test
    void commonTaskDeclaresOnlyFieldsSharedByEveryTaskType() {
        List<String> fields = Arrays.stream(Task.class.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .map(java.lang.reflect.Field::getName)
            .toList();

        assertEquals(
            List.of("id", "key", "displayName", "inputs", "outputs"),
            fields
        );
        assertFalse(fields.contains("route"));
        assertFalse(fields.contains("dependOn"));
        assertFalse(fields.contains("tasks"));
    }

    @Test
    void allowsTheSameDataKeyAcrossInputAndOutputDirections() {
        Task task = PLUGINS.modelValidator().validate(
            Log.builder()
                .id("task-1")
                .key("transform")
                .message(TemplateExpression.parse("test step"))
                .inputs(List.of(input("payload", DataType.STRING)))
                .outputs(List.of(Output.create(
                    "payload",
                    DataType.STRING
                )))
                .build()
        );

        assertEquals("payload", task.inputs().getFirst().getKey());
        assertEquals("payload", task.outputs().getFirst().getKey());
    }

    @Test
    void validatesProvidedAndDeclaredRuntimeOutputs() {
        Task task = Log.builder()
            .id("task-1")
            .key("wait")
            .message(TemplateExpression.parse("test step"))
            .outputs(List.of(Output.create("decision", DataType.STRING)))
            .build();

        assertDoesNotThrow(() ->
            task.validateOutputs(Map.of("decision", "approved"))
        );
        assertDoesNotThrow(() -> task.validateOutputs(Map.of()));
        assertThrows(
            WorkflowException.class,
            () -> task.validateOutputs(null)
        );
        assertThrows(
            WorkflowException.class,
            () -> task.validateOutputs(Map.of(
                "decision",
                "approved",
                "comment",
                "extra"
            ))
        );
        assertThrows(
            WorkflowException.class,
            () -> task.validateOutputs(Map.of("decision", true))
        );

        Task numericTask = Log.builder()
            .id("task-2")
            .key("numeric-wait")
            .message(TemplateExpression.parse("test step"))
            .outputs(List.of(Output.create("count", DataType.LONG)))
            .build();
        assertEquals(
            1L,
            numericTask.validateOutputs(Map.of("count", 1)).get("count")
        );
        assertThrows(
            WorkflowException.class,
            () -> numericTask.validateOutputs(Map.of("count", 1.5))
        );
    }

    @Test
    void providesAPublicNoArgsConstructorForFrameworkBinding() {
        for (Class<? extends Task> type : List.of(
            Log.class,
            Pause.class
        )) {
            var constructor = assertDoesNotThrow(
                () -> type.getConstructor()
            );
            assertTrue(
                Modifier.isPublic(constructor.getModifiers())
            );
        }
    }

    private static Input<?> input(String key, DataType type) {
        return JsonObject.FromMap(
            Map.of(
                "key", key,
                "type", type.name(),
                "displayName", key,
                "required", false
            )
        ).asObject(Input.class);
    }
}
