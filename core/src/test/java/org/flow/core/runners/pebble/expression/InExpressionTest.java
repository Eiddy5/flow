package org.flow.core.runners.pebble.expression;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.flows.State;
import org.flow.core.runners.VariableRenderer;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
public class InExpressionTest {
    @Inject
    VariableRenderer variableRenderer;

    @Test
    void inList() throws IllegalVariableEvaluationException {
        Map<String, Object> vars = new HashMap<>();
        vars.put("state", State.Type.SUCCESS);

        String render = variableRenderer.render("{{ state isIn ['SUCCESS', 'WARNING'] }}", vars);

        assertThat(render).isEqualTo("true");

        render = variableRenderer.render("{{ state isIn ['FAILED', 'KILLED'] }}", vars);

        assertThat(render).isEqualTo("false");
    }

    @Test
    void inMap() throws IllegalVariableEvaluationException {
        Map<String, Object> vars = new HashMap<>();
        vars.put("map", Map.of("key", "value"));
        vars.put("key", "key");
        vars.put("value", "value");

        String render = variableRenderer.render("{{ key isIn map }}", vars);

        assertThat(render).isEqualTo("true");

        render = variableRenderer.render("{{ value isIn map }}", vars);

        assertThat(render).isEqualTo("true");
    }

}
