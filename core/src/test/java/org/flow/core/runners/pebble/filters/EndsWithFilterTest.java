package org.flow.core.runners.pebble.filters;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.runners.VariableRenderer;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@FlowTest
class EndsWithFilterTest {
    @Inject
    VariableRenderer variableRenderer;

    @Test
    void out() throws IllegalVariableEvaluationException {
        Boolean render = Boolean.parseBoolean(
            variableRenderer.render("{{ \"Hello World\" | endsWith(\"World\") }}", Map.of())
        );

        assertThat(render).isTrue();
    }

    @Test
    void shouldReturnFalseWhenInputIsNull() throws IllegalVariableEvaluationException {
        // Given / When
        String render = variableRenderer.render("{{ null | endsWith('x') }}", Map.of());

        // Then
        assertThat(render).isEqualTo("false");
    }

    @Test
    void shouldThrowWhenValueArgumentIsNull() {
        // Given / When / Then
        assertThatThrownBy(() -> variableRenderer.render("{{ 'abc' | endsWith(value=null) }}", Map.of()))
            .isInstanceOf(IllegalVariableEvaluationException.class)
            .hasMessageContaining("The argument 'value' is required");
    }
}