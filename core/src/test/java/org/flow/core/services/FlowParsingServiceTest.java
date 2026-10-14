package org.flow.core.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import org.flow.core.exceptions.FlowProcessingException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.flows.FlowWithSource;
import org.flow.core.models.property.Property;
import org.flow.plugin.core.log.Log;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class FlowParsingServiceTest {
    @Inject
    private FlowParsingService flowParsingService;

    @Test
    void shouldNotInjectAnythingGivenFlowMap() throws FlowProcessingException {
        // Given
        Map<String, Object> task = new HashMap<>();
        task.put("id", "log");
        task.put("type", Log.class.getName());
        task.put("message", "hello");

        Map<String, Object> flow = new HashMap<>();
        flow.put("id", "test");
        flow.put("namespace", "org.flow.unittest");
        flow.put("tasks", new ArrayList<>(List.of(task)));

        // When
        Map<String, Object> injected = flowParsingService.injectPluginVersions(null, "org.flow.unittest", flow);

        // Then nothing is injected: plugin versioning is EE-only, the open-source implementation is a no-op
        assertThat(injected).isSameAs(flow);
        assertThat(task).doesNotContainKey("version");
    }

    @Test
    void shouldIgnorePluginDefaultsWhenParsingLeniently() throws FlowProcessingException {
        // Given a legacy flow source using the removed 'pluginDefaults' keyword
        String source = """
            id: test
            namespace: org.flow.unittest
            tasks:
              - id: log
                type: org.flow.plugin.core.log.Log
                message: explicit
            pluginDefaults:
              - type: org.flow.plugin.core.log.Log
                values:
                  level: WARN
            """;

        // When parsed leniently (read path), the flow still parses but nothing is injected
        FlowWithSource parsed = flowParsingService.parse(null, source, false);

        // Then the task keeps its own default level: the WARN default value is not injected
        Log task = (Log) parsed.getTasks().getFirst();
        assertThat(task.getLevel()).isEqualTo(Property.ofValue(Level.INFO));
        assertThat(parsed.getSource()).isEqualTo(source);
    }

    @Test
    void shouldParseFlowWithDigitsOnlyNamespace() throws FlowProcessingException {
        // A digits-only namespace is read from the YAML as an Integer; it must not break parsing with a raw cast.
        String source = """
            id: test
            namespace: 132312312
            tasks:
              - id: log
                type: org.flow.plugin.core.log.Log
                message: hi
            """;

        FlowWithSource parsed = flowParsingService.parse(null, source, false);

        assertThat(parsed.getNamespace()).isEqualTo("132312312");
    }
}
