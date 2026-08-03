package org.cses.flow.core.plugins;

import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.tasks.RouteExpression;
import org.cses.flow.core.domains.tasks.Task;

import java.util.List;
import java.util.Map;

/**
 * Plugin extension point for materializing one concrete Task type.
 */
public interface TaskExtension extends Plugin {

    @Override
    default Class<? extends Plugin> extensionPoint() {
        return TaskExtension.class;
    }

    Task create(
        String id,
        String parentId,
        String key,
        List<Input<?>> inputs,
        List<Output> outputs,
        RouteExpression route,
        List<String> dependOn,
        Map<String, ?> properties,
        List<? extends Task> tasks
    );

    Task rehydrate(
        String id,
        String parentId,
        String key,
        List<Input<?>> inputs,
        List<Output> outputs,
        RouteExpression route,
        List<String> dependOn,
        Map<String, ?> properties,
        List<? extends Task> tasks
    );

    Map<String, Object> properties(Task task);
}
