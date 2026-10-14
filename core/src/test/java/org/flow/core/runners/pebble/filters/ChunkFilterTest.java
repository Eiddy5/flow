package org.flow.core.runners.pebble.filters;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.runners.VariableRenderer;
import org.flow.core.utils.Rethrow;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@FlowTest
class ChunkFilterTest {
    @Inject
    VariableRenderer variableRenderer;

    @Test
    void out() throws IllegalVariableEvaluationException {
        Map<String, Object> vars = Map.of(
            "list", Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9)
        );

        String render = variableRenderer.render("{{ list | chunk(2) }}", vars);

        assertThat(render).isEqualTo("[[1,2],[3,4],[5,6],[7,8],[9]]");
    }

    @Test
    void exception() {
        assertThrows(IllegalVariableEvaluationException.class, () ->
        {
            Rethrow.throwSupplier(() ->
            {
                variableRenderer.render("{{ test | chunk(2) }}", Map.of("test", 1));
                return null;
            }).get();
        });
    }

    @Test
    void chunkWithIntegerVariable() throws IllegalVariableEvaluationException {
        // Reproducer for issue: Integer variable causing ClassCastException
        Map<String, Object> vars = Map.of(
            "max_items", Integer.valueOf(2),
            "list", Arrays.asList(1, 2, 3, 4, 5)
        );

        String render = variableRenderer.render("{{ list | chunk(max_items) }}", vars);

        assertThat(render).isEqualTo("[[1,2],[3,4],[5]]");
    }

}