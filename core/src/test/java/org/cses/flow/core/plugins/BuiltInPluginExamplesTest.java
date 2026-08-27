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
    void everyBuiltInPluginProvidesDeployableFullFlowYamlExamples() {
        DefaultPluginRegistry registry = new DefaultPluginRegistry(
            BUILT_IN_PLUGINS
        );
        Context context = builtInContext();

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
                    Map<String, Object> definition = YamlParser.parse(source);
                    assertCompleteFlowDefinition(definition, example);
                    Flow flow = context.deploy(
                        "plugin-example-company",
                        "plugin-example-flow-" + exampleIndex,
                        definition,
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

    private static void assertCompleteFlowDefinition(
        Map<String, Object> source,
        PluginExample example
    ) {
        assertTrue(example.full(), "Plugin examples must be full Flows");
        assertTrue(source.get("key") instanceof String);
        assertTrue(source.get("tasks") instanceof List<?>);
        assertEquals(1, ((List<?>) source.get("tasks")).size());
        assertNoSystemFields(source);
    }

    private static void assertNoSystemFields(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    assertFalse(
                        SYSTEM_FIELDS.contains(key),
                        "Plugin examples must not declare " + key
                    );
                }
                assertNoSystemFields(entry.getValue());
            }
        } else if (value instanceof Iterable<?> values) {
            values.forEach(BuiltInPluginExamplesTest::assertNoSystemFields);
        }
    }
}
