package org.flow.core.runners.pebble.functions;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.runners.VariableRenderer;
import org.flow.core.serializers.JacksonMapper;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@MicronautTest
class FetchContextFunctionTest {
    @Inject
    VariableRenderer variableRenderer;

    @Test
    void fromString() throws IllegalVariableEvaluationException, JsonProcessingException {
        String render = variableRenderer.render("{{ printContext() }}", Map.of("test", "value", "array", List.of("a", "b", "c")));
        assertThat(JacksonMapper.toMap(render).get("test")).isEqualTo("value");
        assertThat(JacksonMapper.toMap(render).get("array")).isEqualTo(List.of("a", "b", "c"));
    }
}
