package org.cses.flow.core.plugins;

import io.micronaut.context.annotation.Requires;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginRegistryTest {

    @Test
    void resolvesTheExactCanonicalClassName() {
        PluginRegistry registry = registry(
            new AutomaticTask(),
            new SpecialTask()
        );

        assertSame(
            AutomaticTask.class,
            registry.resolve(AutomaticTask.class.getName(), Task.class)
        );
        assertSame(
            SpecialTask.class,
            registry.resolve(
                SpecialTask.class.getCanonicalName(),
                SpecialExtension.class
            )
        );
    }

    @Test
    void groupsPluginsByTheirRealPackagesInDeterministicOrder() {
        PluginRegistry registry = registry(
            new SpecialTask(),
            new AutomaticTask()
        );

        assertEquals(
            List.of(
                SpecialTask.class.getPackageName(),
                AutomaticTask.class.getPackageName()
            ),
            registry.plugins().stream()
                .map(RegisteredPlugin::packageName)
                .toList()
        );
        RegisteredPlugin specialPackage = registry.plugins().getFirst();
        assertEquals(
            List.of(SpecialTask.class.getCanonicalName()),
            specialPackage.tasks().stream()
                .map(PluginMetadata::canonicalType)
                .toList()
        );
        RegisteredPlugin automaticPackage = registry.plugins().getLast();
        assertEquals(
            List.of(AutomaticTask.class.getCanonicalName()),
            automaticPackage.tasks().stream()
                .map(PluginMetadata::canonicalType)
                .toList()
        );
        PluginMetadata<?> metadata = registry.findMetadata(
            SpecialTask.class.getCanonicalName()
        ).orElseThrow();
        assertEquals(
            SpecialTask.class.getPackageName(),
            metadata.packageName()
        );
        assertEquals("Special task", metadata.title());
        assertEquals("Used to verify plugin metadata.", metadata.description());
        assertSame(Task.class, metadata.baseClass());
    }

    @Test
    void normalizesOptionalDisplayMetadata() {
        PluginMetadata<Task> metadata = new PluginMetadata<>(
            AutomaticTask.class,
            Task.class,
            " ",
            " "
        );

        assertEquals("AutomaticTask", metadata.title());
        assertEquals("", metadata.description());
    }

    @Test
    void doesNotNormalizeOrAliasTypes() {
        PluginRegistry registry = registry(
            new AutomaticTask()
        );

        IllegalArgumentException shortType = assertThrows(
            IllegalArgumentException.class,
            () -> registry.resolve("AUTO", Task.class)
        );
        assertEquals(
            "No plugin registered for type: AUTO",
            shortType.getMessage()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.resolve(
                AutomaticTask.class.getName().toLowerCase(),
                Task.class
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.resolve(
                " " + AutomaticTask.class.getName() + " ",
                Task.class
            )
        );
    }

    @Test
    void rejectsDuplicateCanonicalTypes() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> registry(
                new AutomaticTask(),
                new AutomaticTask()
            )
        );

        assertTrue(exception.getMessage().contains(
            "Duplicate plugin type '" + AutomaticTask.class.getName()
        ));
    }

    @Test
    void checksTheRequestedPluginCapability() {
        PluginRegistry registry = registry(
            new AutomaticTask(),
            new SpecialTask()
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> registry.resolve(
                AutomaticTask.class.getCanonicalName(),
                SpecialExtension.class
            )
        );
        assertTrue(exception.getMessage().contains("is not a"));
    }

    @Test
    void rejectsClassesWithoutTheDiscoveryAnnotation() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> registry(new UnannotatedPlugin())
        );

        assertTrue(exception.getMessage().contains("missing @Plugin"));
    }

    @Test
    void doesNotCreateSyntheticPackageGroups() {
        assertTrue(new DefaultPluginRegistry(List.of()).plugins().isEmpty());
    }

    private interface SpecialExtension
        extends org.cses.flow.core.plugins.Plugin {
    }

    @Plugin(
        title = "Special task",
        description = "Used to verify plugin metadata."
    )
    @Requires(property = "flow.test.special-plugin", value = "true")
    public static final class SpecialTask
        extends Task implements RunnableTask, SpecialExtension {

        public SpecialTask() {
        }

        @Override
        public RunResult run(RunContext context) {
            return RunResult.success(Map.of());
        }
    }

    public static final class UnannotatedPlugin
        implements org.cses.flow.core.plugins.Plugin {

        public UnannotatedPlugin() {
        }
    }

    private static DefaultPluginRegistry registry(
        org.cses.flow.core.plugins.Plugin... plugins
    ) {
        return new DefaultPluginRegistry(List.of(plugins));
    }
}
