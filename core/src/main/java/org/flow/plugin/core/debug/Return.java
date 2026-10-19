package org.flow.plugin.core.debug;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;

import org.flow.plugin.api.annotations.Example;
import org.flow.plugin.api.annotations.Metric;
import org.flow.plugin.api.annotations.Plugin;
import org.flow.core.models.executions.metrics.Counter;
import org.flow.core.models.executions.metrics.Timer;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.RunnableTask;
import org.flow.core.models.tasks.Task;
import org.flow.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Return a value for debugging purposes.",
    description = """
        Render a templated string and return it so you can quickly inspect or reuse values during a flow.

        Handy for troubleshooting (the rendered value is logged) or for generating small payloads such as headers or tokens that downstream tasks expect. Combine with template filters to control whitespace or formatting before the value is passed along."""
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: debug_value
                namespace: company.team

                tasks:
                  - id: return
                    type: org.flow.plugin.core.debug.Return
                    format: "{{ task.id }} > {{ taskrun.startDate }}"
                """
        ),
        @Example(
            full = true,
            code = """
                id: return
                namespace: company.team

                inputs:
                  - id: token
                    type: STRING
                    required: false
                    displayName: "API Token"

                  - id: username
                    type: STRING
                    displayName: "Username"
                    required: false

                  - id: password
                    type: STRING
                    displayName: "Password"
                    required: false

                tasks:
                  - id: compute_header
                    type: org.flow.plugin.core.debug.Return
                    format: >-
                      {%- if inputs.token is not empty -%}
                      Bearer {{ inputs.token }}
                      {%- elseif inputs.username is not empty and inputs.password is not empty -%}
                      Basic {{ (inputs.username + ':' + inputs.password) | base64encode }}
                      {%- endif -%}
                """
        )
    },
    metrics = {
        @Metric(name = "length", type = Counter.TYPE),
        @Metric(name = "duration", type = Timer.TYPE)
    }
)
public class Return extends Task implements RunnableTask<Return.Output> {
    @Schema(
        title = "The templated string to render."
    )
    private Property<String> format;

    @Override
    public Return.Output run(RunContext runContext) throws Exception {
        long start = System.nanoTime();

        Logger logger = runContext.logger();

        String render = runContext.render(format).as(String.class).orElse(null);
        logger.debug(render);

        long end = System.nanoTime();

        runContext
            .metric(Counter.of("length", Optional.ofNullable(render).map(String::length).orElse(0)))
            .metric(Timer.of("duration", Duration.ofNanos(end - start)));

        return Output.builder()
            .value(render)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements org.flow.core.models.tasks.Output {
        @Schema(
            title = "The generated string."
        )
        private String value;
    }
}
