package org.cses.flow.extensions.tasks;

import jakarta.inject.Singleton;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.tasks.RouteExpression;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.TaskExtension;

import java.util.List;
import java.util.Map;

/**
 * Registers and materializes the AUTO Task type.
 */
@Singleton
public final class AutomaticTaskPlugin implements TaskExtension {

    @Override
    public String type() {
        return AutomaticTask.TYPE;
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
        return AutomaticTask.create(
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
        return AutomaticTask.rehydrate(
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
        if (!(task instanceof AutomaticTask)) {
            throw new IllegalArgumentException(
                "AUTO plugin requires AutomaticTask"
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
}
