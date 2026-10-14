package org.flow.core.runners.pebble.filters;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.runners.VariableRenderer;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
public class Sha512FilterTest {
    @Inject
    VariableRenderer variableRenderer;

    @Test
    void out() throws IllegalVariableEvaluationException {
        String render = variableRenderer.render("{{ \"hello\" | sha512 }}", Map.of());

        assertThat(render.equals("hello")).isFalse();
    }
}
