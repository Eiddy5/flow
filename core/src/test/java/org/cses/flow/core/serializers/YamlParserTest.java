package org.cses.flow.core.serializers;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.extensions.log.Log;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext;

class YamlParserTest {

    @Test
    void parsesADeeplyImmutableMappingWithoutBusinessKnowledge() {
        builtInContext();
        Map<String, Object> parsed = YamlParser.parse(
            """
            key: release-flow
            tasks:
              - key: prepare
                settings:
                  retries: 2
            """
        );

        assertEquals("release-flow", parsed.get("key"));
        List<?> tasks = (List<?>) parsed.get("tasks");
        Map<?, ?> task = (Map<?, ?>) tasks.getFirst();
        assertEquals("prepare", task.get("key"));
        assertThrows(
            UnsupportedOperationException.class,
            () -> parsed.put("status", "DRAFT")
        );
        assertThrows(
            UnsupportedOperationException.class,
            task::clear
        );
    }

    @Test
    void bindsFlowDirectlyThroughJacksonWithoutFlowSpecificParser() {
        builtInContext();
        Flow flow = YamlParser.parse(
            """
            key: release-flow
            tasks:
              - key: prepare
                type: %s
            """.formatted(Log.class.getName()),
            Flow.class
        );

        assertNull(flow.id());
        assertTrue(flow.draft());
        assertEquals("release-flow", flow.key());
        assertFalse(flow.tasks().isEmpty());
        assertEquals("prepare", flow.tasks().getFirst().key());
        assertFalse(flow.tasks().getFirst().id().isBlank());
    }

    /**
     * Binds parsed source maps, including an empty map, and rejects null
     * arguments or fields whose types cannot be bound.
     */
    @Test
    void bindsParsedMapsAndRejectsInvalidArguments() {
        builtInContext();
        Map<String, Object> fields = Map.of(
            "key", "bound-flow",
            "tasks", List.of(Map.of(
                "key", "prepare",
                "type", Log.class.getName()
            ))
        );

        Flow bound = YamlParser.bind(fields, Flow.class);
        Flow empty = YamlParser.bind(Map.of(), Flow.class);

        assertEquals("bound-flow", bound.key());
        assertEquals("prepare", bound.tasks().getFirst().key());
        assertNull(empty.key());
        assertThrows(
            NullPointerException.class,
            () -> YamlParser.bind(null, Flow.class)
        );
        assertThrows(
            NullPointerException.class,
            () -> YamlParser.bind(fields, null)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> YamlParser.bind(
                Map.of("tasks", "not-a-list"),
                Flow.class
            )
        );
    }

    @Test
    void rejectsDuplicateKeysAndNonMappingDocuments() {
        builtInContext();
        assertThrows(
            IllegalArgumentException.class,
            () -> YamlParser.parse(
                """
                key: first
                key: second
                """
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> YamlParser.parse("- first\n- second")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> YamlParser.parse(
                """
                key: first
                ---
                key: second
                """
            )
        );
    }

    @Test
    void reportsTheYamlLocationAndOriginalParserFailure() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> YamlParser.parse(
                """
                key: first
                key: second
                """
            )
        );

        assertTrue(exception.getMessage().contains("line"));
        assertTrue(exception.getMessage().contains("column"));
        assertTrue(
            exception.getMessage().contains("Duplicate field 'key'")
        );
    }
}
