package org.cses.flow.controller.plugins;

import org.cses.flow.controller.plugins.PluginModels.PluginDetailsView;
import org.cses.flow.controller.plugins.PluginModels.RegisteredPluginView;
import org.cses.flow.core.plugins.DefaultPluginRegistry;
import org.cses.flow.core.plugins.PluginModule;
import org.cses.flow.core.serializers.JacksonMapper;
import org.cses.flow.core.serializers.PluginSchemaGenerator;
import org.cses.flow.core.services.plugins.PluginService;
import org.cses.flow.extensions.log.Log;
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
                new Log()
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
                Log.class.getPackageName()
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
            Log.class.getPackageName(),
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
            Log.class.getCanonicalName()
        );
        assertEquals(
            Log.class.getPackageName(),
            details.metadata().packageName()
        );
        assertEquals(1, details.examples().size());
        assertEquals(
            "记录流程消息",
            details.examples().getFirst().title()
        );
        assertEquals(
            List.of("""
                key: log-flow
                tasks:
                  - key: write-log
                    type: org.cses.flow.extensions.log.Log
                    message: "流程已进入自动处理阶段"
                """),
            details.examples().getFirst().code()
        );
        assertEquals("yaml", details.examples().getFirst().lang());
        assertEquals(true, details.examples().getFirst().full());
    }
}
