package org.flow.core.runners.pebble.expression;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.runners.VariableRenderer;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class LessThanExpressionTest {

    @Inject
    VariableRenderer variableRenderer;

    @Test
    void shouldCompareStrings() throws IllegalVariableEvaluationException {
        Map<String, Object> vars = Map.of("left", "a", "right", "b");

        assertThat(variableRenderer.render("{{ left < right }}", vars)).isEqualTo("true");
        assertThat(variableRenderer.render("{{ right < left }}", vars)).isEqualTo("false");
        assertThat(variableRenderer.render("{{ left < left }}", vars)).isEqualTo("false");
    }

    @Test
    void shouldCompareNumbers() throws IllegalVariableEvaluationException {
        Map<String, Object> vars = Map.of("three", 3, "five", 5);

        assertThat(variableRenderer.render("{{ three < five }}", vars)).isEqualTo("true");
        assertThat(variableRenderer.render("{{ five < three }}", vars)).isEqualTo("false");
    }
}
