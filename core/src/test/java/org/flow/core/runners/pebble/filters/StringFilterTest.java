package org.flow.core.runners.pebble.filters;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.runners.VariableRenderer;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
public class StringFilterTest {

    @Inject
    VariableRenderer variableRenderer;

    static Stream<Arguments> source() {
        return Stream.of(
            Arguments.of("{{ 12.3 | string | className }}", String.class.getName()),
            Arguments.of("{{ {\"field\":\"hello\"} | string | className }}", String.class.getName())
        );
    }

    @ParameterizedTest
    @MethodSource("source")
    void run(String exp, String expected) throws IllegalVariableEvaluationException {
        String render = variableRenderer.render(exp, Map.of());
        assertThat(render).isEqualTo(expected);
    }
}
