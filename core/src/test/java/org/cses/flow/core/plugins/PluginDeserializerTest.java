package org.cses.flow.core.plugins;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.extensions.flow.Pause;
import org.cses.flow.extensions.log.Log;
import org.cses.flow.core.serializers.YamlParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDeserializerTest {

    @Test
    void bindsConcreteAndNestedTasksWithoutFlowDefinitionDeserializer() {
        TaskPluginTestSupport.Context context = builtInContext();

        Task task = context.jacksonMapper().convertValue(
            Map.of(
                "id", "pause-id",
                "key", "pause",
                "type", Pause.class.getCanonicalName(),
                "pause", Map.of(
                    "id", "child-id",
                    "key", "prepare",
                    "type", Log.class.getCanonicalName(), "message", "test step"
                ),
                "resume", List.of(Map.of(
                    "key", "decision",
                    "type", " string ",
                    "displayName", "Decision",
                    "required", true
                ))
            ),
            Task.class
        );

        Pause pause = assertInstanceOf(Pause.class, task);
        assertEquals("pause-id", pause.id());
        assertEquals("prepare", pause.pause().key());
        Input<?> input = pause.resume().getFirst();
        assertEquals("decision", input.getKey());
        assertEquals("Decision", input.getDisplayName());
    }

    @Test
    void appliesSourceIdentityRulesThroughTheRegisteredYamlModule() {
        TaskPluginTestSupport.Context context = builtInContext();

        Flow flow = YamlParser.parse(
            """
            key: source-context
            tasks:
              - key: task
                type: %s
                message: "test step"
            """.formatted(Log.class.getCanonicalName()),
            Flow.class
        );

        assertFalse(flow.tasks().getFirst().id().isBlank());
        assertThrows(
            IllegalArgumentException.class,
            () -> YamlParser.parse(
                """
                key: source-context
                tasks:
                  - id: caller-owned-id
                    key: task
                    type: %s
                    message: "test step"
                """.formatted(Log.class.getCanonicalName()),
                Flow.class
            )
        );
    }

    @Test
    void resolvesOnlyRegisteredExactPluginTypes() {
        TaskPluginTestSupport.Context context = builtInContext();

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> context.jacksonMapper().convertValue(
                Map.of(
                    "id", "task-id",
                    "key", "task",
                    "type", "LOG"
                ),
                Task.class
            )
        );

        assertTrue(
            exception.getMessage().contains("No plugin registered for type: LOG")
        );
    }

    @Test
    void keepsInputTypeNormalizationInsideTheJacksonDefinitionContract() {
        TaskPluginTestSupport.Context context = builtInContext();

        Input<?> input = context.jacksonMapper().convertValue(
            Map.of(
                "key", "retry-count",
                "type", " integer ",
                "defaultValue", 3,
                "min", 0,
                "max", 5
            ),
            Input.class
        );

        input.validateDefinition();
        assertEquals("retry-count", input.getKey());
        assertEquals("retry-count", input.getDisplayName());
        assertEquals(3, input.getDefaultValue());
    }
}
