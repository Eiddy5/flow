package org.flow.plugin.core.templating;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.flow.core.context.TestRunContextFactory;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.Output;
import org.flow.core.runners.RunContext;
import org.flow.plugin.core.debug.Return;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@FlowTest
class TemplatedTaskTest {

    @Inject
    private TestRunContextFactory runContextFactory;

    @Test
    void templatedType() throws Exception {
        RunContext runContext = runContextFactory.of(Map.of("type", "org.flow.plugin.core.debug.Return"));
        TemplatedTask templatedTask = TemplatedTask.builder()
            .id("template")
            .type(TemplatedTask.class.getName())
            .spec(Property.ofExpression("""
                type: {{ type }}
                format: It's alive!"""))
            .build();

        Output output = templatedTask.run(runContext);

        assertThat(output).isNotNull();
        assertThat(output).isInstanceOf(Return.Output.class);
        assertThat(((Return.Output) output).getValue()).isEqualTo("It's alive!");
    }

    @Test
    void templatedFlowable() {
        RunContext runContext = runContextFactory.of();
        TemplatedTask templatedTask = TemplatedTask.builder()
            .id("template")
            .type(TemplatedTask.class.getName())
            .spec(Property.ofValue("""
                type: org.flow.plugin.core.flow.Pause
                delay: PT10S"""))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> templatedTask.run(runContext));
        assertThat(exception.getMessage()).isEqualTo("The templated task must be a runnable task");
    }

    @Test
    void templatedTemplated() {
        RunContext runContext = runContextFactory.of();
        TemplatedTask templatedTask = TemplatedTask.builder()
            .id("template")
            .type(TemplatedTask.class.getName())
            .spec(Property.ofValue("""
                type: org.flow.plugin.core.templating.TemplatedTask
                spec: whatever"""))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> templatedTask.run(runContext));
        assertThat(exception.getMessage()).isEqualTo("The templated task cannot be of type 'org.flow.plugin.core.templating.TemplatedTask'");
    }

}