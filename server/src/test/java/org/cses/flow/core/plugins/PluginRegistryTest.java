package org.cses.flow.core.plugins;

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
        PluginRegistry registry = new DefaultPluginRegistry(List.of(
            new AutomaticTask(),
            new SpecialTask()
        ));

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
    void describesTheRegisteredCoreBundleInCanonicalOrder() {
        PluginRegistry registry = new DefaultPluginRegistry(List.of(
            new SpecialTask(),
            new AutomaticTask()
        ));

        assertEquals(1, registry.plugins().size());
        RegisteredPlugin core = registry.plugins().getFirst();
        assertEquals(RegisteredPlugin.CORE_NAME, core.name());
        assertEquals(
            List.of(
                AutomaticTask.class.getCanonicalName(),
                SpecialTask.class.getCanonicalName()
            ).stream().sorted().toList(),
            core.tasks().stream()
                .map(PluginMetadata::canonicalType)
                .toList()
        );
        PluginMetadata<?> metadata = registry.findMetadata(
            SpecialTask.class.getCanonicalName()
        ).orElseThrow();
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
        PluginRegistry registry = new DefaultPluginRegistry(List.of(
            new AutomaticTask()
        ));

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
            () -> new DefaultPluginRegistry(List.of(
                new AutomaticTask(),
                new AutomaticTask()
            ))
        );

        assertTrue(exception.getMessage().contains(
            "Duplicate plugin type '" + AutomaticTask.class.getName()
        ));
    }

    @Test
    void checksTheRequestedPluginCapability() {
        PluginRegistry registry = new DefaultPluginRegistry(List.of(
            new AutomaticTask(),
            new SpecialTask()
        ));

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
            () -> new DefaultPluginRegistry(List.of(
                new UnannotatedPlugin()
            ))
        );

        assertTrue(exception.getMessage().contains("missing @Plugin"));
    }

    private interface SpecialExtension
        extends org.cses.flow.core.plugins.Plugin {
    }

    @Plugin(
        title = "Special task",
        description = "Used to verify plugin metadata."
    )
    public static final class SpecialTask
        extends Task implements RunnableTask, SpecialExtension {

        public SpecialTask() {
        }

        @Override
        public RunResult run(RunContext context) {
            return RunResult.completed(Map.of());
        }
    }

    public static final class UnannotatedPlugin
        implements org.cses.flow.core.plugins.Plugin {

        public UnannotatedPlugin() {
        }
    }
}
