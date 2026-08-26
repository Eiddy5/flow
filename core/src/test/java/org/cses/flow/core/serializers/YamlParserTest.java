package org.cses.flow.core.serializers;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.extensions.tasks.AutomaticTask;
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

    private YamlParser parser = new YamlParser(
        builtInContext().jacksonMapper()
    );

    @Test
    void parsesADeeplyImmutableMappingWithoutBusinessKnowledge() {
        Map<String, Object> parsed = parser.parse(
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
        Flow flow = parser.parse(
            """
            key: release-flow
            tasks:
              - key: prepare
                type: %s
            """.formatted(AutomaticTask.class.getName()),
            Flow.class
        );

        assertNull(flow.id());
        assertTrue(flow.draft());
        assertEquals("release-flow", flow.key());
        assertFalse(flow.tasks().isEmpty());
        assertEquals("prepare", flow.tasks().getFirst().key());
        assertFalse(flow.tasks().getFirst().id().isBlank());
    }

    @Test
    void rejectsDuplicateKeysAndNonMappingDocuments() {
        assertThrows(
            IllegalArgumentException.class,
            () -> parser.parse(
                """
                key: first
                key: second
                """
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> parser.parse("- first\n- second")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> parser.parse(
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
            () -> parser.parse(
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
