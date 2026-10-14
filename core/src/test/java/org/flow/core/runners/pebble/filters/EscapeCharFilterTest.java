package org.flow.core.runners.pebble.filters;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.runners.VariableRenderer;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@FlowTest
class EscapeCharFilterTest {
    @Inject
    private VariableRenderer variableRenderer;

    @ParameterizedTest
    @MethodSource("provideValidTypes")
    void validTypes(String type, String input, String expected) throws IllegalVariableEvaluationException {
        String render = variableRenderer.render(
            "{{ " + input + " | escapeChar('" + type + "') }}",
            Map.of()
        );

        assertThat(render).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = { "foo", "" })
    void invalidTypes(String type) {
        assertThrows(
            IllegalVariableEvaluationException.class,
            () -> variableRenderer.render(
                "{{ 'Hello' | escapeChar('" + type + "') }}",
                Map.of()
            )
        );
    }

    private static Stream<Arguments> provideValidTypes() {
        return Stream.of(
            Arguments.of("single", "\"L'eau c'est la vie\"", "L\\'eau c\\'est la vie"), // codespell:ignore
            Arguments.of("double", "'\"Hello\"'", "\\\"Hello\\\""),
            Arguments.of("shell", "\"L'eau c'est la vie\"", "L'\\''eau c'\\''est la vie"), // codespell:ignore
            Arguments.of("single", "''", ""),
            Arguments.of("double", "''", ""),
            Arguments.of("shell", "''", "")
        );
    }
}
