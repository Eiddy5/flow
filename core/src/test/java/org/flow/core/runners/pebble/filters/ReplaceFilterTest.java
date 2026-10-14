package org.flow.core.runners.pebble.filters;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.runners.VariableRenderer;
import org.flow.core.utils.RegexTestUtils;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@FlowTest
class ReplaceFilterTest {
    @Inject
    VariableRenderer variableRenderer;

    @Test
    void string() throws IllegalVariableEvaluationException {
        String render = variableRenderer.render("{{ 'john doe is john doe' | replace({'john': 'jane'}) }}", Map.of());

        assertThat(render).isEqualTo("jane doe is jane doe");
    }

    @Test
    void regexp() throws IllegalVariableEvaluationException {
        String render = variableRenderer.render("{{ 'aa1bb2cc3dd4ee5' | replace({'(\\d)': '-$1-'}, regexp=true) }}", Map.of());

        assertThat(render).isEqualTo("aa-1-bb-2-cc-3-dd-4-ee-5-");
    }

    @Test
    void regexpReDoSShouldTimeout() {
        try {
            RegexTestUtils.resetAndSetTimeout(Duration.ofMillis(200));
            String evilInput = "a".repeat(25) + "b";

            assertThatThrownBy(
                () -> variableRenderer.render(
                    "{{ '" + evilInput + "' | replace({'(.*a){25}': 'x'}, regexp=true) }}", Map.of()
                )
            )
                .isInstanceOf(IllegalVariableEvaluationException.class)
                .hasMessageContaining("timed out");
        } finally {
            RegexTestUtils.reset();
        }
    }
}
