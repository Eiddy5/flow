package org.cses.flow.core.domains.tasks;

import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.cses.flow.extensions.tasks.PauseTask;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskTest {

    @Test
    void normalizesAndProtectsDefinitionState() {
        List<Input> inputs = new ArrayList<>(
            List.of(Input.create("request", "JSON"))
        );
        List<Output> outputs = new ArrayList<>(
            List.of(Output.create("result", "STRING"))
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
            List.of(Input.create("request", "JSON")),
            task.inputs()
        );
        assertEquals(
            List.of(Output.create("result", "STRING")),
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
            () -> task.inputs().add(Input.create("other", "STRING"))
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
            List.of(Input.create("request", "JSON")),
            List.of(Output.create("decision", "STRING")),
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
            List.of(Input.rehydrate("request", "JSON")),
            List.of(Output.rehydrate("decision", "STRING")),
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
                    Input.create("request", "JSON"),
                    Input.create("request", "STRING")
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
            List.of(Input.create("payload", "JSON")),
            List.of(Output.create("payload", "JSON")),
            RouteExpression.direct(),
            List.of(),
            List.of()
        );

        assertEquals("payload", task.inputs().getFirst().getKey());
        assertEquals("payload", task.outputs().getFirst().getKey());
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
}
