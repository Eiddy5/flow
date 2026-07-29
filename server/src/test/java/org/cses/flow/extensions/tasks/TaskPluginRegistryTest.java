package org.cses.flow.extensions.tasks;

import org.cses.flow.core.domains.tasks.TaskTypeDispatcher;
import org.cses.flow.core.domains.tasks.RouteExpression;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.cses.flow.extensions.tasks.TaskPluginTestSupport.builtInDispatcher;

class TaskPluginRegistryTest {

    @Test
    void registryNormalizesTaskTypes() {
        AutomaticTaskPlugin plugin = new AutomaticTaskPlugin();
        TaskPluginRegistry registry = new TaskPluginRegistry(
            List.of(plugin)
        );

        assertSame(plugin, registry.find(" auto ").orElseThrow());
    }

    @Test
    void registryRejectsDuplicateNormalizedTypes() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> new TaskPluginRegistry(List.of(
                new AutomaticTaskPlugin(),
                new AutomaticTaskPlugin()
            ))
        );

        assertEquals(
            "Duplicate Task plugin type 'AUTO': "
                + AutomaticTaskPlugin.class.getName()
                + " and "
                + AutomaticTaskPlugin.class.getName(),
            exception.getMessage()
        );
    }

    @Test
    void dispatcherRejectsUnknownDefinitionAndPersistedTypes() {
        TaskTypeDispatcher dispatcher = builtInDispatcher();

        IllegalArgumentException definitionError = assertThrows(
            IllegalArgumentException.class,
            () -> dispatcher.dispatch(
                "task-1",
                null,
                "unknown",
                "MISSING",
                List.of(),
                List.of(),
                RouteExpression.direct(),
                List.of(),
                Map.of(),
                List.of()
            )
        );
        assertEquals(
            "No Task plugin registered for type: MISSING",
            definitionError.getMessage()
        );

        IllegalStateException persistedError = assertThrows(
            IllegalStateException.class,
            () -> dispatcher.restore(
                "task-1",
                null,
                "unknown",
                "MISSING",
                List.of(),
                List.of(),
                RouteExpression.direct(),
                List.of(),
                Map.of(),
                List.of()
            )
        );
        assertEquals(
            "No Task plugin registered for persisted type: MISSING",
            persistedError.getMessage()
        );
    }
}
