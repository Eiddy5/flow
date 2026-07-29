package org.cses.flow.infrastructure.repositories.flows.postgres.entries;

import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.tasks.RouteExpression;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.domains.tasks.TaskTypeDispatcher;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;
import org.paas.json.JsonObjects;

import java.util.List;

import static org.cses.flow.extensions.tasks.TaskPluginTestSupport.builtInDispatcher;
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
        Task task = AutomaticTask.create(
            "task-id",
            null,
            "approval",
            List.of(Input.create("request", "JSON")),
            List.of(Output.create("decision", "STRING")),
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

        assertEquals(task, entry.toDomain(dispatcher, List.of()));
        assertEquals(List.of("prepare"), entry.dependOn.asStrings());
        assertEquals(
            "outputs.decision == \"approved\"",
            entry.route
        );
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
}
