package org.flow.core.runners.pebble.filters;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.runners.VariableRenderer;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class UrlDecodeFilter {
    @Inject
    VariableRenderer variableRenderer;

    @Test
    void urldecode() throws IllegalVariableEvaluationException {
        String render = variableRenderer.render("{{ 'Flow rulez !' | urlencode | urldecode }}", Map.of());
        assertThat(render).isEqualTo("Flow rulez !");
    }
}
