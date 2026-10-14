package org.flow.core.runners.pebble.expression;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.runners.VariableRenderer;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@FlowTest
class NullCoalescingExpressionTest {
    @Inject
    VariableRenderer variableRenderer;

    @Test
    void firstDefined() throws IllegalVariableEvaluationException {
        ImmutableMap<String, Object> vars = ImmutableMap.of(
            "block", ImmutableMap.of("test", ImmutableMap.of("child", "awesome")),
            "inner", "test"
        );

        String render = variableRenderer.render("{{ inner.bla ?? block.test.child }}", vars);

        assertThat(render).isEqualTo("awesome");

        render = variableRenderer.render("{{ block.test.child ?? inner.bla }}", vars);

        assertThat(render).isEqualTo("awesome");

        assertThrows(IllegalVariableEvaluationException.class, () ->
        {
            variableRenderer.render("{{ missing ?? missing2 }}", vars);
        });
    }

    @Test
    void firstDefinedEval() throws IllegalVariableEvaluationException {
        ImmutableMap<String, Object> vars = ImmutableMap.of(
            "block", ImmutableMap.of("test", ImmutableMap.of("child", "awesome")),
            "inner", "test"
        );

        String render = variableRenderer.render("{{ block.test.child ?? null }}", vars);
        assertThat(render).isEqualTo("awesome");

        render = variableRenderer.render("{{ block[inner].child ?? null }}", vars);
        assertThat(render).isEqualTo("awesome");

        render = variableRenderer.render("{{ block[missing].child ?? block[inner].child }}", vars);
        assertThat(render).isEqualTo("awesome");

        render = variableRenderer.render("{{ block[missing].child ?? block[missing2].child ?? block[inner].child }}", vars);
        assertThat(render).isEqualTo("awesome");

        render = variableRenderer.render("{{ missing ?? block.test.child }}", vars);
        assertThat(render).isEqualTo("awesome");

        assertThrows(IllegalVariableEvaluationException.class, () ->
        {
            variableRenderer.render("{{ missing ?? missing2 }}", vars);
        });
    }

    @Test
    void emptyObject() throws IllegalVariableEvaluationException {
        ImmutableMap<String, Object> vars = ImmutableMap.of(
            "block", Map.of()
        );

        String render = variableRenderer.render("{{ block ?? 'UNDEFINED' }}", vars);

        assertThat(render).isEqualTo("{}");
    }

    @Test
    void nullOrUndefined() throws IllegalVariableEvaluationException {
        Map<String, Object> vars = new HashMap<>();
        vars.put("null", null);

        String render = variableRenderer.render("{{ null ?? 'IS NULL' }}", vars);

        assertThat(render).isEqualTo("IS NULL");

        render = variableRenderer.render("{{ undefined ?? 'IS UNDEFINED' }}", vars);

        assertThat(render).isEqualTo("IS UNDEFINED");
    }
}