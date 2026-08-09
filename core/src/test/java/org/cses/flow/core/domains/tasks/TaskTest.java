package org.cses.flow.core.domains.tasks;

import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.cses.flow.core.plugins.TestNotificationTask;
import org.cses.flow.extensions.tasks.AutomaticTask;
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
        Task child = automatic("child-id", "child");
        Task task = PLUGINS.modelValidator().validate(
            AutomaticTask.builder()
                .id("task-id")
                .key("task")
                .inputs(List.of(input("request", DataType.STRING)))
                .outputs(List.of(Output.create(
                    "result",
                    DataType.STRING
                )))
                .route(TaskRoute.direct())
                .dependOn(List.of("prepare"))
                .tasks(List.of(child))
                .build()
        );

        assertEquals(AutomaticTask.class.getName(), task.getType());
        assertEquals("task", task.key());
        assertTrue(task.declaresOutput("result"));
        assertEquals("DIRECT", task.route().source());
        assertEquals(List.of("prepare"), task.dependOn());
        assertEquals(List.of("child"), task.tasks().stream()
            .map(Task::key)
            .toList());
        assertThrows(
            UnsupportedOperationException.class,
            () -> task.inputs().add(input("other", DataType.STRING))
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> task.dependOn().add("other")
        );
        assertFalse(Arrays.stream(Task.class.getMethods())
            .anyMatch(method -> method.getName().startsWith("set")));
    }

    @Test
    void usesDefinitionValueEqualityAcrossPersistenceBoundaries() {
        Task first = AutomaticTask.builder()
            .id("task-1")
            .key("approval")
            .inputs(List.of(input("request", DataType.STRING)))
            .outputs(List.of(Output.create("decision", DataType.STRING)))
            .route(TaskRoute.parse(
                "outputs.decision == \"approved\""
            ))
            .dependOn(List.of("prepare"))
            .build();
        Task restored = AutomaticTask.builder()
            .id("task-1")
            .key("approval")
            .inputs(List.of(input("request", DataType.STRING)))
            .outputs(List.of(Output.rehydrate(
                "decision",
                DataType.STRING
            )))
            .route(TaskRoute.parse(
                "outputs.decision == \"approved\""
            ))
            .dependOn(List.of("prepare"))
            .build();

        assertEquals(first, restored);
        assertEquals(first.hashCode(), restored.hashCode());
    }

    @Test
    void activeValidatorRejectsInvalidDirectlyCreatedTasks() {
        assertThrows(
            IllegalArgumentException.class,
            () -> PLUGINS.modelValidator().validate(
                AutomaticTask.builder()
                    .id("task-1")
                    .key("approval")
                    .inputs(List.of(
                        input("request", DataType.STRING),
                        input("request", DataType.INTEGER)
                    ))
                    .build()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PLUGINS.modelValidator().validate(
                AutomaticTask.builder()
                    .id("task-1")
                    .key("approval")
                    .dependOn(List.of("prepare", "prepare"))
                    .build()
            )
        );
        assertThrows(
            jakarta.validation.ConstraintViolationException.class,
            () -> PLUGINS.modelValidator().validate(
                AutomaticTask.builder().id(" ").key("task").build()
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
    void allowsTheSameDataKeyAcrossInputAndOutputDirections() {
        Task task = PLUGINS.modelValidator().validate(
            AutomaticTask.builder()
                .id("task-1")
                .key("transform")
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
        Task task = AutomaticTask.builder()
            .id("task-1")
            .key("wait")
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

        Task numericTask = AutomaticTask.builder()
            .id("task-2")
            .key("numeric-wait")
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
            AutomaticTask.class,
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

    private static AutomaticTask automatic(String id, String key) {
        return AutomaticTask.builder().id(id).key(key).build();
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
