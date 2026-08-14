package org.cses.flow.core.plugins;

import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.cses.flow.core.serializers.YamlParser;
import org.cses.flow.extensions.flow.Loop;
import org.cses.flow.extensions.flow.LoopUntil;
import org.cses.flow.extensions.flow.Parallel;
import org.cses.flow.extensions.flow.Pause;
import org.cses.flow.extensions.log.Log;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltInPluginExamplesTest {

    private static final List<Plugin> BUILT_IN_PLUGINS = List.of(
        new AutomaticTask(),
        new Log(),
        new Loop(),
        new LoopUntil(),
        new Parallel(),
        new Pause()
    );
    private static final Set<String> SYSTEM_FIELDS = Set.of(
        "id",
        "parentId",
        "taskId"
    );

    @Test
    void everyBuiltInPluginProvidesDeployableYamlExamples() {
        DefaultPluginRegistry registry = new DefaultPluginRegistry(
            BUILT_IN_PLUGINS
        );
        Context context = builtInContext();
        YamlParser yamlParser = new YamlParser(context.jacksonMapper());

        List<PluginMetadata<Task>> metadata = registry.plugins().stream()
            .flatMap(group -> group.tasks().stream())
            .toList();
        assertEquals(BUILT_IN_PLUGINS.size(), metadata.size());

        int exampleIndex = 0;
        for (PluginMetadata<Task> plugin : metadata) {
            assertFalse(
                plugin.examples().isEmpty(),
                plugin.canonicalType() + " must declare an example"
            );
            for (PluginExample example : plugin.examples()) {
                assertEquals("yaml", example.lang());
                for (String source : example.code()) {
                    Map<String, Object> task = taskDefinition(
                        yamlParser.parse(source),
                        plugin,
                        example,
                        exampleIndex
                    );
                    Flow flow = context.deploy(
                        "plugin-example-company",
                        "plugin-example-flow-" + exampleIndex,
                        Map.of(
                            "key", "plugin-example-" + exampleIndex,
                            "tasks", List.of(task)
                        ),
                        null,
                        ActorRef.create("example-user", "Example User"),
                        exampleIndex + 1L
                    );
                    assertEquals(
                        plugin.type(),
                        flow.tasks().getFirst().getClass()
                    );
                    exampleIndex++;
                }
            }
        }
        assertTrue(exampleIndex >= BUILT_IN_PLUGINS.size());
    }

    private static Map<String, Object> taskDefinition(
        Map<String, Object> source,
        PluginMetadata<Task> plugin,
        PluginExample example,
        int exampleIndex
    ) {
        for (String systemField : SYSTEM_FIELDS) {
            assertFalse(
                source.containsKey(systemField),
                "Plugin examples must not declare " + systemField
            );
        }
        if (example.full()) {
            assertEquals(plugin.canonicalType(), source.get("type"));
            assertTrue(source.get("key") instanceof String);
            return source;
        }

        assertFalse(source.containsKey("key"));
        assertFalse(source.containsKey("type"));
        Map<String, Object> augmented = new LinkedHashMap<>();
        augmented.put("key", "example-task-" + exampleIndex);
        augmented.put("type", plugin.canonicalType());
        augmented.putAll(source);
        return Map.copyOf(augmented);
    }
}
