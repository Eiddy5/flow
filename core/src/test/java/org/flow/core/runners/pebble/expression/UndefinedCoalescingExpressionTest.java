package org.flow.core.runners.pebble.expression;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.runners.VariableRenderer;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class UndefinedCoalescingExpressionTest {
    @Inject
    VariableRenderer variableRenderer;

    @Test
    void nullOrUndefined() throws IllegalVariableEvaluationException {
        Map<String, Object> vars = new HashMap<>();
        vars.put("null", null);

        String render = variableRenderer.render("{{ null ??? 'IS NULL' }}", vars);

        assertThat(render).isEqualTo("");

        render = variableRenderer.render("{{ undefined ??? 'IS UNDEFINED' }}", vars);

        assertThat(render).isEqualTo("IS UNDEFINED");
    }
}