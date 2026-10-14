package org.flow.core.runners.pebble.filters;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableMap;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.runners.VariableRenderer;
import org.flow.core.serializers.JacksonMapper;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class ValuesFilterTest {
    @Inject
    VariableRenderer variableRenderer;

    @Test
    void map() throws IllegalVariableEvaluationException, JsonProcessingException {
        ZonedDateTime date = ZonedDateTime.parse("2013-09-08T16:19:00+02").withZoneSameLocal(ZoneId.systemDefault());

        ImmutableMap<String, Object> vars = ImmutableMap.of(
            "vars", ImmutableMap.of(
                "second", Map.of(
                    "string", "string",
                    "bool", true,
                    "date", date,
                    "map", Map.of(
                        "string", "string",
                        "int", 1,
                        "float", 1.123F
                    )
                )
            )
        );

        String render = variableRenderer.render("{{ vars.second.map | values }}", vars);
        List<Object> list = JacksonMapper.ofJson().readValue(render, new TypeReference<>() {
        });
        assertThat(list).contains("string");
        assertThat(list).contains(1.123);

        render = variableRenderer.render("{{ vars.second | values }}", vars);
        list = JacksonMapper.ofJson().readValue(render, new TypeReference<>() {
        });
        assertThat(list).contains("string");
        assertThat(list).contains(true);
    }
}
