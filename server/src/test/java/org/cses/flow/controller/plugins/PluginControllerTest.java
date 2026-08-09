package org.cses.flow.controller.plugins;

import io.micronaut.validation.validator.Validator;
import org.cses.flow.controller.plugins.PluginModels.PluginDetailsView;
import org.cses.flow.controller.plugins.PluginModels.RegisteredPluginView;
import org.cses.flow.core.plugins.DefaultPluginRegistry;
import org.cses.flow.core.plugins.PluginModule;
import org.cses.flow.core.plugins.TestNotificationTask;
import org.cses.flow.core.serializers.JacksonMapper;
import org.cses.flow.core.serializers.PluginSchemaGenerator;
import org.cses.flow.core.services.plugins.PluginService;
import org.cses.flow.core.validations.ModelValidator;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginControllerTest {

    @Test
    void exposesRealPackagePathsInCatalogGroupsAndTaskMetadata() {
        DefaultPluginRegistry registry = new DefaultPluginRegistry(
            List.of(new TestNotificationTask(), new AutomaticTask())
        );
        ModelValidator validator = new ModelValidator(
            Validator.getInstance()
        );
        JacksonMapper mapper = new JacksonMapper(
            new PluginModule(registry, validator)
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
                TestNotificationTask.class.getPackageName(),
                AutomaticTask.class.getPackageName()
            ),
            plugins.stream()
                .map(RegisteredPluginView::packageName)
                .toList()
        );
        assertEquals(
            TestNotificationTask.class.getPackageName(),
            plugins.getFirst().tasks().getFirst().packageName()
        );
        assertEquals(
            AutomaticTask.class.getPackageName(),
            plugins.getLast().tasks().getFirst().packageName()
        );

        PluginDetailsView details = controller.plugin(
            TestNotificationTask.class.getCanonicalName()
        );
        assertEquals(
            TestNotificationTask.class.getPackageName(),
            details.metadata().packageName()
        );
    }
}
