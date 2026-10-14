package org.flow.core.runners.pebble.filters;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.runners.VariableRenderer;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class SubstringFilterTest {
    @Inject
    VariableRenderer variableRenderer;

    @Test
    void substringBefore() throws IllegalVariableEvaluationException {
        String render = variableRenderer.render("{{ 'a.b.c' | substringBefore('.') }}", Map.of());
        assertThat(render).isEqualTo("a");
    }

    @Test
    void substringAfter() throws IllegalVariableEvaluationException {
        String render = variableRenderer.render("{{ 'a.b.c' | substringAfter(separator='.') }}", Map.of());
        assertThat(render).isEqualTo("b.c");
    }

    @Test
    void substringBeforeLast() throws IllegalVariableEvaluationException {
        String render = variableRenderer.render("{{ 'a.b.c' | substringBeforeLast(separator='.') }}", Map.of());
        assertThat(render).isEqualTo("a.b");

        render = variableRenderer.render("{{ 'a.b.c' | substringBeforeLast('.') }}", Map.of());
        assertThat(render).isEqualTo("a.b");
    }

    @Test
    void substringAfterLast() throws IllegalVariableEvaluationException {
        String render = variableRenderer.render("{{ 'a.b.c' | substringAfterLast('.') }}", Map.of());
        assertThat(render).isEqualTo("c");
    }
}
