package org.flow.core.runners.pebble.expression;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.runners.VariableRenderer;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class GreaterThanEqualsExpressionTest {

    @Inject
    VariableRenderer variableRenderer;

    @Test
    void shouldCompareStrings() throws IllegalVariableEvaluationException {
        Map<String, Object> vars = Map.of("left", "b", "right", "a");

        assertThat(variableRenderer.render("{{ left >= right }}", vars)).isEqualTo("true");
        assertThat(variableRenderer.render("{{ left >= left }}", vars)).isEqualTo("true");
        assertThat(variableRenderer.render("{{ right >= left }}", vars)).isEqualTo("false");
    }

    @Test
    void shouldCompareNumbers() throws IllegalVariableEvaluationException {
        Map<String, Object> vars = Map.of("five", 5, "three", 3);

        assertThat(variableRenderer.render("{{ five >= three }}", vars)).isEqualTo("true");
        assertThat(variableRenderer.render("{{ five >= five }}", vars)).isEqualTo("true");
        assertThat(variableRenderer.render("{{ three >= five }}", vars)).isEqualTo("false");
    }
}
