package org.flow.plugin.core.execution;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.plugin.api.annotations.Example;
import org.flow.plugin.api.annotations.Metric;
import org.flow.plugin.api.annotations.Plugin;
import org.flow.plugin.api.annotations.PluginProperty;
import org.flow.core.models.executions.metrics.Counter;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.RunnableTask;
import org.flow.core.models.tasks.Task;
import org.flow.core.models.tasks.VoidOutput;
import org.flow.core.runners.RunContext;
import org.flow.core.utils.TruthUtils;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Assert boolean expressions against execution data.",
    description = """
        Renders each string in `conditions` and coerces the result to boolean (empty string/0 is false, everything else true). Any falsy assertion logs an error, emits `failed`/`success` metrics, and stops the flow with an exception.

        Use `errorMessage` to append extra context to the thrown error."""
)
@Plugin(
    examples = {
        @Example(
            title = "Assert based on inputs data",
            full = true,
            code = """
                    id: assert
                    namespace: company.team


                    inputs:
                      - id: param
                        type: STRING
                        required: true

                    tasks:
                      - id: fail
                        type: org.flow.plugin.core.execution.Assert
                        conditions:
                          - "{{ inputs.param == 'ok' }}"
                          - "{{ 1 + 1 == 3 }}"
                """
        )
    },
    metrics = {
        @Metric(name = "failed", type = Counter.TYPE),
        @Metric(name = "success", type = Counter.TYPE)
    }
)
public class Assert extends Task implements RunnableTask<VoidOutput> {
    @Schema(
        title = "List of assertion condition, must coerce to a boolean.",
        description = "Boolean coercion allows 0, -0, and '' to coerce to false, all other values to coerce to true."
    )
    @NotNull
    @PluginProperty(dynamic = true)
    private List<String> conditions;

    @Schema(title = "Optional error message")
    private Property<String> errorMessage;

    @Override
    public VoidOutput run(RunContext runContext) throws Exception {
        AtomicInteger failed = new AtomicInteger(0);
        AtomicInteger success = new AtomicInteger(0);

        conditions
            .forEach(s ->
            {
                try {
                    String renderer = runContext.render(s);

                    if (TruthUtils.isFalsy(renderer)) {
                        runContext.logger().error("Assertion `{}` failed!", s, renderer);
                        failed.incrementAndGet();
                    } else {
                        success.incrementAndGet();
                    }

                } catch (IllegalVariableEvaluationException e) {
                    runContext.logger().error("Assertion `{}` failed, failed to render `{}`", s, e.getMessage());
                    failed.incrementAndGet();
                }
            });

        runContext.metric(Counter.of("success", success.get()));
        runContext.metric(Counter.of("failed", failed.get()));

        if (failed.get() > 0) {
            throw new Exception(
                failed + " assertion" + (failed.get() > 1 ? "s" : "") + " failed!" +
                    runContext.render(errorMessage).as(String.class).map(r -> "\n" + r).orElse("")
            );
        }

        return null;
    }
}
