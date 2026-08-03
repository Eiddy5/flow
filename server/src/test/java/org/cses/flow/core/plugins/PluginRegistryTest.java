package org.cses.flow.core.plugins;

import org.cses.flow.core.domains.tasks.RouteExpression;
import org.cses.flow.extensions.tasks.AutomaticTaskPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.cses.flow.core.plugins.TaskExtensionTestSupport.builtInDispatcher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginRegistryTest {

    @TempDir
    Path serviceRoot;

    @Test
    void registryNormalizesTypesWithinAnExtensionPoint() {
        AutomaticTaskPlugin plugin = new AutomaticTaskPlugin();
        PluginRegistry registry = new PluginRegistry(List.of(plugin));

        assertSame(
            plugin,
            registry.find(TaskExtension.class, " auto ").orElseThrow()
        );
    }

    @Test
    void registryKeepsTypeNamespacesSeparateByExtensionPoint() {
        AutomaticTaskPlugin taskPlugin = new AutomaticTaskPlugin();
        AuditPlugin auditPlugin = new AuditPlugin();
        PluginRegistry registry = new PluginRegistry(List.of(
            taskPlugin,
            auditPlugin
        ));

        assertSame(
            taskPlugin,
            registry.find(TaskExtension.class, "auto").orElseThrow()
        );
        assertSame(
            auditPlugin,
            registry.find(AuditExtension.class, "auto").orElseThrow()
        );
    }

    @Test
    void registryRejectsDuplicateNormalizedTypes() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> new PluginRegistry(List.of(
                new AutomaticTaskPlugin(),
                new AutomaticTaskPlugin()
            ))
        );

        assertEquals(
            "Duplicate plugin type 'AUTO' for extension point '"
                + TaskExtension.class.getName()
                + "': "
                + AutomaticTaskPlugin.class.getName()
                + " and "
                + AutomaticTaskPlugin.class.getName(),
            exception.getMessage()
        );
    }

    @Test
    void registryDiscoversClasspathServiceProviders() throws Exception {
        Path serviceFile = serviceRoot.resolve(
            "META-INF/services/" + Plugin.class.getName()
        );
        Files.createDirectories(serviceFile.getParent());
        Files.writeString(
            serviceFile,
            AutomaticTaskPlugin.class.getName() + System.lineSeparator(),
            StandardCharsets.UTF_8
        );

        try (URLClassLoader classLoader = new URLClassLoader(
            new java.net.URL[]{serviceRoot.toUri().toURL()},
            getClass().getClassLoader()
        )) {
            PluginRegistry registry = new PluginRegistry(
                List.of(),
                classLoader
            );

            assertInstanceOf(
                AutomaticTaskPlugin.class,
                registry.find(
                    TaskExtension.class,
                    "auto"
                ).orElseThrow()
            );
        }
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
            "No Task extension registered for type: MISSING",
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
            "No Task extension registered for persisted type: MISSING",
            persistedError.getMessage()
        );
    }

    private interface AuditExtension extends Plugin {

        @Override
        default Class<? extends Plugin> extensionPoint() {
            return AuditExtension.class;
        }
    }

    private static final class AuditPlugin implements AuditExtension {

        @Override
        public String type() {
            return "AUTO";
        }
    }
}
