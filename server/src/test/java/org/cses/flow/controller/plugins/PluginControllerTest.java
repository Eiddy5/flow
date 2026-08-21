package org.cses.flow.controller.plugins;

import org.cses.flow.controller.plugins.PluginModels.PluginDetailsView;
import org.cses.flow.controller.plugins.PluginModels.RegisteredPluginView;
import org.cses.flow.core.plugins.DefaultPluginRegistry;
import org.cses.flow.core.plugins.PluginModule;
import org.cses.flow.core.serializers.JacksonMapper;
import org.cses.flow.core.serializers.PluginSchemaGenerator;
import org.cses.flow.core.services.plugins.PluginService;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.cses.flow.extensions.flow.Parallel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginControllerTest {

    @Test
    void exposesRealPackagePathsInCatalogGroupsAndTaskMetadata() {
        DefaultPluginRegistry registry = new DefaultPluginRegistry(
            List.of(
                new Parallel(),
                new AutomaticTask()
            )
        );
        JacksonMapper mapper = new JacksonMapper(
            new PluginModule(registry)
        );
        PluginController controller = new PluginController(
            new PluginService(
                registry,
                new PluginSchemaGenerator(mapper)
            )
        );

        List<RegisteredPluginView> plugins = controller.plugins();

        assertEquals(
            List.of(
                Parallel.class.getPackageName(),
                AutomaticTask.class.getPackageName()
            ),
            plugins.stream()
                .map(RegisteredPluginView::packageName)
                .toList()
        );
        assertEquals(
            Parallel.class.getPackageName(),
            plugins.getFirst().tasks().getFirst().packageName()
        );
        assertEquals(
            AutomaticTask.class.getPackageName(),
            plugins.getLast().tasks().getFirst().packageName()
        );
        PluginDetailsView parallel = controller.plugin(
            Parallel.class.getCanonicalName()
        );
        assertEquals(
            List.of("PARALLEL_CHILDREN"),
            parallel.metadata().capabilities()
        );

        PluginDetailsView details = controller.plugin(
            AutomaticTask.class.getCanonicalName()
        );
        assertEquals(
            AutomaticTask.class.getPackageName(),
            details.metadata().packageName()
        );
        assertEquals(1, details.examples().size());
        assertEquals(
            "执行自动步骤",
            details.examples().getFirst().title()
        );
        assertEquals(
            List.of("""
                key: automatic-task-flow
                tasks:
                  - key: automatic-step
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                """),
            details.examples().getFirst().code()
        );
        assertEquals("yaml", details.examples().getFirst().lang());
        assertEquals(true, details.examples().getFirst().full());
    }
}
