package org.cses.flow.core.plugins;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.tasks.RouteExpression;
import org.cses.flow.core.domains.tasks.Task;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Dispatches Task materialization through the installed plugin registry.
 */
@Singleton
@Requires(missingBeans = TaskTypeDispatcher.class)
public final class RegisteredTaskTypeDispatcher
    implements TaskTypeDispatcher {

    private final PluginRegistry registry;

    public RegisteredTaskTypeDispatcher(PluginRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Task dispatch(
        String id,
        String parentId,
        String key,
        String type,
        List<Input<?>> inputs,
        List<Output> outputs,
        RouteExpression route,
        List<String> dependOn,
        Map<String, ?> properties,
        List<? extends Task> tasks
    ) {
        TaskExtension plugin = registry.find(
            TaskExtension.class,
            type
        ).orElseThrow(() ->
            new IllegalArgumentException(
                "No Task extension registered for type: " + type
            )
        );
        return requireConsistentTask(
            plugin.create(
                id,
                parentId,
                key,
                inputs,
                outputs,
                route,
                dependOn,
                properties,
                tasks
            ),
            plugin,
            id,
            parentId,
            key,
            inputs,
            outputs,
            route,
            dependOn,
            properties,
            tasks
        );
    }

    @Override
    public Task restore(
        String id,
        String parentId,
        String key,
        String type,
        List<Input<?>> inputs,
        List<Output> outputs,
        RouteExpression route,
        List<String> dependOn,
        Map<String, ?> properties,
        List<? extends Task> tasks
    ) {
        TaskExtension plugin = registry.find(
            TaskExtension.class,
            type
        ).orElseThrow(() ->
            new IllegalStateException(
                "No Task extension registered for persisted type: " + type
            )
        );
        return requireConsistentTask(
            plugin.rehydrate(
                id,
                parentId,
                key,
                inputs,
                outputs,
                route,
                dependOn,
                properties,
                tasks
            ),
            plugin,
            id,
            parentId,
            key,
            inputs,
            outputs,
            route,
            dependOn,
            properties,
            tasks
        );
    }

    private static Task requireConsistentTask(
        Task task,
        TaskExtension plugin,
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
        if (task == null) {
            throw new IllegalStateException(
                "Task extension returned null: "
                    + plugin.getClass().getName()
            );
        }
        if (!plugin.type().trim().equalsIgnoreCase(task.type())) {
            throw new IllegalStateException(
                "Task extension type '"
                    + plugin.type()
                    + "' returned Task type '"
                    + task.type()
                    + "'"
            );
        }
        if (!Objects.equals(id, task.id())
            || !Objects.equals(
                java.util.Optional.ofNullable(parentId),
                task.parentId()
            )
            || !Objects.equals(key, task.key())
            || !Objects.equals(inputs, task.inputs())
            || !Objects.equals(outputs, task.outputs())
            || !Objects.equals(route, task.route())
            || !Objects.equals(dependOn, task.dependOn())
            || !Objects.equals(properties, plugin.properties(task))
            || !Objects.equals(tasks, task.tasks())) {
            throw new IllegalStateException(
                "Task extension returned inconsistent fields for type: "
                    + plugin.type()
            );
        }
        return task;
    }

    @Override
    public Map<String, Object> properties(Task task) {
        Objects.requireNonNull(task, "Task");
        TaskExtension plugin = registry.find(
            TaskExtension.class,
            task.type()
        ).orElseThrow(() ->
            new IllegalStateException(
                "No Task extension registered for persisted type: "
                    + task.type()
            )
        );
        Map<String, Object> properties = plugin.properties(task);
        if (properties == null) {
            throw new IllegalStateException(
                "Task extension returned null properties: "
                    + plugin.getClass().getName()
            );
        }
        return Map.copyOf(properties);
    }
}
