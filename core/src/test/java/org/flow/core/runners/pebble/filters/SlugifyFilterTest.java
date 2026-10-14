package org.flow.core.runners.pebble.filters;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.runners.VariableRenderer;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class SlugifyFilterTest {
    @Inject
    VariableRenderer variableRenderer;

    @Test
    void out() throws IllegalVariableEvaluationException {
        String render = variableRenderer.render("{{ \"Test\t\t\ntest*\" | slugify }}", Map.of());

        assertThat(render).isEqualTo("test-test");
    }
}