package org.cses.flow.core.plugins;

import io.micronaut.context.annotation.Requires;
import org.cses.flow.core.domains.tasks.VoidOutput;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.plugins.annotations.Example;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.extensions.log.Log;
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
            new Log(),
            new SpecialTask()
        );

        assertSame(
            Log.class,
            registry.resolve(Log.class.getName(), Task.class)
        );
        assertSame(
            SpecialTask.class,
            registry.resolve(
                SpecialTask.class.getCanonicalName(),
                SpecialExtension.class
            )
        );
    }

    /** 核对内置输入与 Task 按真实包名稳定排序，以及任务元数据不变。 */
    @Test
    void groupsPluginsByTheirRealPackagesInDeterministicOrder() {
        PluginRegistry registry = registry(
            new SpecialTask(),
            new Log()
        );

        assertEquals(
            List.of(
                org.cses.flow.core.domains.flows.inputs.StringInput.class.getPackageName(),
                SpecialTask.class.getPackageName(),
                Log.class.getPackageName()
            ),
            registry.plugins().stream()
                .map(RegisteredPlugin::packageName)
                .toList()
        );
        RegisteredPlugin specialPackage = registry.plugins().get(1);
        assertEquals(
            List.of(SpecialTask.class.getCanonicalName()),
            specialPackage.tasks().stream()
                .map(PluginMetadata::canonicalType)
                .toList()
        );
        RegisteredPlugin logPackage = registry.plugins().getLast();
        assertEquals(
            List.of(Log.class.getCanonicalName()),
            logPackage.tasks().stream()
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
        assertEquals("特殊任务", metadata.title());
        assertEquals("处理特殊显示名称配置", metadata.description());
        assertEquals(List.of("TEST_NOTIFICATION"), metadata.capabilities());
        assertSame(Task.class, metadata.baseClass());
        assertEquals(1, metadata.examples().size());
        PluginExample example = metadata.examples().getFirst();
        assertEquals("配置特殊任务", example.title());
        assertEquals(
            List.of(
                """
                    key: special-flow
                    tasks:
                      - key: special-task
                        type: org.cses.flow.core.plugins.PluginRegistryTest.SpecialTask
                        displayName: Special
                    """,
                """
                    key: alternate-flow
                    tasks:
                      - key: alternate-task
                        type: org.cses.flow.core.plugins.PluginRegistryTest.SpecialTask
                        displayName: Alternate
                    """
            ),
            example.code()
        );
        assertEquals("yaml", example.lang());
        assertEquals(true, example.full());
        assertThrows(
            UnsupportedOperationException.class,
            () -> metadata.examples().add(example)
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> example.code().add("changed: true")
        );
    }

    @Test
    void normalizesOptionalDisplayMetadata() {
        PluginMetadata<Task> metadata = PluginMetadata.from(
            Log.class,
            Task.class,
            " ",
            " "
        );

        assertEquals("Log", metadata.title());
        assertEquals("", metadata.description());
        assertTrue(metadata.examples().isEmpty());
        assertTrue(metadata.capabilities().isEmpty());
    }

    @Test
    void rejectsEmptyExampleSourcesAndLanguages() {
        assertThrows(
            IllegalArgumentException.class,
            () -> PluginExample.from("", List.of(), "yaml", false)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PluginExample.from("", List.of(" "), "yaml", false)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PluginExample.from(
                "",
                List.of("message: example"),
                " ",
                false
            )
        );
    }

    @Test
    void doesNotNormalizeOrAliasTypes() {
        PluginRegistry registry = registry(
            new Log()
        );

        IllegalArgumentException shortType = assertThrows(
            IllegalArgumentException.class,
            () -> registry.resolve("LOG", Task.class)
        );
        assertEquals(
            "No plugin registered for type: LOG",
            shortType.getMessage()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.resolve(
                Log.class.getName().toLowerCase(),
                Task.class
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.resolve(
                " " + Log.class.getName() + " ",
                Task.class
            )
        );
    }

    @Test
    void rejectsDuplicateCanonicalTypes() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> registry(
                new Log(),
                new Log()
            )
        );

        assertTrue(exception.getMessage().contains(
            "Duplicate plugin type '" + Log.class.getName()
        ));
    }

    @Test
    void checksTheRequestedPluginCapability() {
        PluginRegistry registry = registry(
            new Log(),
            new SpecialTask()
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> registry.resolve(
                Log.class.getCanonicalName(),
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

    /** 没有 Task 时目录仍提供固定内置 Input。 */
    @Test
    void doesNotCreateSyntheticPackageGroups() {
        var groups = new DefaultPluginRegistry(List.of()).plugins();
        assertEquals(1, groups.size());
        assertEquals(9, groups.getFirst().inputs().size());
        assertTrue(groups.getFirst().tasks().isEmpty());
    }

    private interface SpecialExtension
        extends org.cses.flow.core.plugins.Plugin {
    }

    @Plugin(
        title = "特殊任务",
        description = "处理特殊显示名称配置",
        capabilities = "TEST_NOTIFICATION",
        examples = {
            @Example(
                title = "配置特殊任务",
                code = {
                    """
                        key: special-flow
                        tasks:
                          - key: special-task
                            type: org.cses.flow.core.plugins.PluginRegistryTest.SpecialTask
                            displayName: Special
                        """,
                    """
                        key: alternate-flow
                        tasks:
                          - key: alternate-task
                            type: org.cses.flow.core.plugins.PluginRegistryTest.SpecialTask
                            displayName: Alternate
                        """
                },
                full = true
            )
        }
    )
    @Requires(property = "flow.test.special-plugin", value = "true")
    public static class SpecialTask
        extends Task implements RunnableTask<VoidOutput>, SpecialExtension {

        public SpecialTask() {
        }

        @Override
        public VoidOutput run(RunContext context) {
            return VoidOutput.from();
        }
    }

    public static class UnannotatedPlugin
        implements org.cses.flow.core.plugins.Plugin {

        public UnannotatedPlugin() {
        }
    }

    /**
     * 为 Task 独立技术测试创建只包含指定 Task 的目录。
     * @param plugins 非空 Task 集合，不修改
     * @return 包含固定内置 Input 的独立注册表
     */
    private static DefaultPluginRegistry registry(
        org.cses.flow.core.plugins.Plugin... plugins
    ) {
        return new DefaultPluginRegistry(List.of(plugins));
    }
}
