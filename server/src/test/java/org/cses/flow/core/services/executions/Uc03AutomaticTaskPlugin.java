package org.cses.flow.core.services.executions;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.tasks.RunContext;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RouteExpression;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.TaskExtension;
import org.cses.flow.extensions.tasks.AutomaticTaskPlugin;

import java.util.List;
import java.util.Map;

/**
 * Deterministic AUTO Task implementation for UC-03.
 */
@Singleton
@Requires(property = "flow.uc03.auto-task", value = "true")
@Replaces(AutomaticTaskPlugin.class)
final class Uc03AutomaticTaskPlugin implements TaskExtension {

    private static final String TYPE = "AUTO";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Task create(
        String id,
        String parentId,
        String key,
        List<Input<?>> inputs,
        List<Output> outputs,
        RouteExpression route,
        List<String> dependOn,
        Map<String, ?> properties,
        List<? extends Task> tasks
    ) {
        requireNoProperties(properties);
        return Uc03AutomaticTask.create(
            id,
            parentId,
            key,
            inputs,
            outputs,
            route,
            dependOn,
            tasks
        );
    }

    @Override
    public Task rehydrate(
        String id,
        String parentId,
        String key,
        List<Input<?>> inputs,
        List<Output> outputs,
        RouteExpression route,
        List<String> dependOn,
        Map<String, ?> properties,
        List<? extends Task> tasks
    ) {
        requireNoProperties(properties);
        return Uc03AutomaticTask.rehydrate(
            id,
            parentId,
            key,
            inputs,
            outputs,
            route,
            dependOn,
            tasks
        );
    }

    @Override
    public Map<String, Object> properties(Task task) {
        if (!(task instanceof Uc03AutomaticTask)) {
            throw new IllegalArgumentException(
                "AUTO UC plugin requires Uc03AutomaticTask"
            );
        }
        return Map.of();
    }

    private static void requireNoProperties(Map<String, ?> properties) {
        if (properties != null && !properties.isEmpty()) {
            throw new IllegalArgumentException(
                "AUTO Task contains unsupported fields: "
                    + properties.keySet()
            );
        }
    }

    private static final class Uc03AutomaticTask
        extends Task implements RunnableTask {

        private Uc03AutomaticTask(
            String id,
            String parentId,
            String key,
            List<? extends Input<?>> inputs,
            List<? extends Output> outputs,
            RouteExpression route,
            List<String> dependOn,
            List<? extends Task> tasks
        ) {
            super(
                id,
                parentId,
                key,
                TYPE,
                inputs,
                outputs,
                route,
                dependOn,
                tasks
            );
        }

        private static Uc03AutomaticTask create(
            String id,
            String parentId,
            String key,
            List<? extends Input<?>> inputs,
            List<? extends Output> outputs,
            RouteExpression route,
            List<String> dependOn,
            List<? extends Task> tasks
        ) {
            return new Uc03AutomaticTask(
                id,
                parentId,
                key,
                inputs,
                outputs,
                route,
                dependOn,
                tasks
            );
        }

        private static Uc03AutomaticTask rehydrate(
            String id,
            String parentId,
            String key,
            List<? extends Input<?>> inputs,
            List<? extends Output> outputs,
            RouteExpression route,
            List<String> dependOn,
            List<? extends Task> tasks
        ) {
            return new Uc03AutomaticTask(
                id,
                parentId,
                key,
                inputs,
                outputs,
                route,
                dependOn,
                tasks
            );
        }

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
                    "Missing expected AUTO input output: " + output
                );
            }
            return outputs.get(output);
        }
    }
}
