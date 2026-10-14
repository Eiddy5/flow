package org.flow.core.runners.pebble.filters;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.runners.VariableRenderer;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
public class FlattenFilterTest {
    @Inject
    VariableRenderer variableRenderer;

    @Test
    void flatten() throws IllegalVariableEvaluationException {
        Map<String, Object> vars = Map.of(
            "nestedList", Arrays.asList(
                "You're doing great! Keep it up!",
                "Sending positive vibes your way!",
                Arrays.asList("You're awesome!", "Keep shining!"),
                "Believe in yourself!",
                Arrays.asList("You're making a difference!", "You've got this!"),
                "You're capable of amazing things!",
                "Stay positive and keep going!",
                "You're doing fantastic!"
            )
        );

        String render = variableRenderer.render("{{ nestedList | flatten | first }}", vars);
        String expected = "You're doing great! Keep it up!";

        assertThat(render).isEqualTo(expected);
    }
}
