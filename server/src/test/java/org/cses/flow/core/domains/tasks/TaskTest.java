package org.cses.flow.core.domains.tasks;

import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.cses.flow.extensions.tasks.PauseTask;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;
import org.paas.json.JsonObject;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskTest {

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    @Test
    void normalizesAndProtectsDefinitionState() {
        List<Input<?>> inputs = new ArrayList<>(
            List.of(input("request", DataType.STRING))
        );
        List<Output> outputs = new ArrayList<>(
            List.of(Output.create("result", DataType.STRING))
        );
        List<String> dependOn = new ArrayList<>(List.of("prepare"));
        List<Task> children = new ArrayList<>(
            List.of(AutomaticTask.create(
                "child-id",
                "task-id",
                "child",
                List.of(),
                List.of(),
                RouteExpression.direct(),
                List.of(),
                List.of()
            ))
        );

        Task task = AutomaticTask.create(
            "task-id",
            null,
            " task ",
            inputs,
            outputs,
            RouteExpression.parse(" "),
            dependOn,
            children
        );
        inputs.clear();
        outputs.clear();
        dependOn.clear();
        children.clear();

        assertEquals("task", task.key());
        assertEquals("AUTO", task.type());
        assertEquals(
            List.of(input("request", DataType.STRING)),
            task.inputs()
        );
        assertEquals(
            List.of(Output.create("result", DataType.STRING)),
            task.outputs()
        );
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
    }

    @Test
    void usesDefinitionValueEqualityAcrossPersistenceBoundaries() {
        Task first = AutomaticTask.create(
            "task-1",
            "parent-1",
            "approval",
            List.of(input("request", DataType.STRING)),
            List.of(Output.create("decision", DataType.STRING)),
            RouteExpression.parse(
                "outputs.decision == \"approved\""
            ),
            List.of("prepare"),
            List.of()
        );
        Task restored = AutomaticTask.rehydrate(
            "task-1",
            "parent-1",
            "approval",
            List.of(input("request", DataType.STRING)),
            List.of(Output.rehydrate("decision", DataType.STRING)),
            RouteExpression.parse(
                "outputs.decision == \"approved\""
            ),
            List.of("prepare"),
            List.of()
        );

        assertEquals(first, restored);
        assertEquals(first.hashCode(), restored.hashCode());
    }

    @Test
    void rejectsDuplicateDataAndDependencyKeys() {
        assertThrows(
            IllegalArgumentException.class,
            () -> AutomaticTask.create(
                "task-1",
                null,
                "approval",
                List.of(
                    input("request", DataType.STRING),
                    input("request", DataType.INTEGER)
                ),
                List.of(),
                RouteExpression.direct(),
                List.of(),
                List.of()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> AutomaticTask.create(
                "task-1",
                null,
                "approval",
                List.of(),
                List.of(),
                RouteExpression.direct(),
                List.of("prepare", "prepare"),
                List.of()
            )
        );
    }

    @Test
    void allowsTheSameDataKeyAcrossInputAndOutputDirections() {
        Task task = AutomaticTask.create(
            "task-1",
            null,
            "transform",
            List.of(input("payload", DataType.STRING)),
            List.of(Output.create("payload", DataType.STRING)),
            RouteExpression.direct(),
            List.of(),
            List.of()
        );

        assertEquals("payload", task.inputs().getFirst().getKey());
        assertEquals("payload", task.outputs().getFirst().getKey());
    }

    @Test
    void validatesProvidedAndDeclaredRuntimeOutputs() {
        Task task = PauseTask.create(
            "task-1",
            null,
            "wait",
            List.of(),
            List.of(Output.create("decision", DataType.STRING)),
            RouteExpression.direct(),
            List.of(),
            List.of()
        );

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

        Task numericTask = PauseTask.create(
            "task-2",
            null,
            "numeric-wait",
            List.of(),
            List.of(Output.create("count", DataType.LONG)),
            RouteExpression.direct(),
            List.of(),
            List.of()
        );
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
    void exposesOnlyCompleteStaticCreationAndRehydration() {
        assertTrue(List.of(AutomaticTask.class, PauseTask.class).stream()
            .flatMap(type -> Arrays.stream(type.getDeclaredConstructors()))
            .allMatch(constructor ->
                Modifier.isPrivate(constructor.getModifiers())
            ));
        assertThrows(
            IllegalArgumentException.class,
            () -> AutomaticTask.create(
                " ",
                null,
                "task",
                List.of(),
                List.of(),
                RouteExpression.direct(),
                List.of(),
                List.of()
            )
        );
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
