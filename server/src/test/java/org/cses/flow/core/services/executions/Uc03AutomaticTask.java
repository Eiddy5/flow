package org.cses.flow.core.services.executions;

import io.micronaut.context.annotation.Requires;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.annotations.Plugin;

import java.util.Map;

/**
 * Deterministic Task plugin used by UC-03.
 */
@Plugin
@Requires(property = "flow.uc03.auto-task", value = "true")
@SuperBuilder
@NoArgsConstructor
public final class Uc03AutomaticTask extends Task implements RunnableTask {

    @Override
    public RunResult run(RunContext context) {
        return switch (key()) {
            case "prepare-input" -> RunResult.completed(
                Map.of("payload", "prepared")
            );
            case "target-success" -> RunResult.completed(Map.of(
                "result",
                "processed-" + nestedOutput(
                    context.inputs(),
                    "payload"
                )
            ));
            case "observe-output" -> RunResult.completed(Map.of(
                "observed",
                nestedOutput(context.inputs(), "result")
            ));
            case "target-fail" -> RunResult.failed(
                "uc03-explicit-failure"
            );
            case "target-throw" -> throw new IllegalStateException(
                "uc03-unhandled-failure"
            );
            default -> RunResult.completed(Map.of());
        };
    }

    private static Object nestedOutput(
        Map<String, Object> inputs,
        String output
    ) {
        Object raw = inputs.get("outputs");
        if (!(raw instanceof Map<?, ?> outputs)
            || !outputs.containsKey(output)) {
            throw new IllegalStateException(
                "Missing expected Task input output: " + output
            );
        }
        return outputs.get(output);
    }
}
