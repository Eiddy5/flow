package org.cses.flow.extensions.tasks;

import org.cses.flow.core.domains.tasks.TaskPlugin;
import org.cses.flow.core.domains.tasks.TaskTypeDispatcher;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the production plugin dispatch chain without a Micronaut context.
 */
public final class TaskPluginTestSupport {

    private TaskPluginTestSupport() {
    }

    public static TaskTypeDispatcher builtInDispatcher() {
        return withPlugins();
    }

    public static TaskTypeDispatcher withPlugins(
        TaskPlugin... additionalPlugins
    ) {
        List<TaskPlugin> plugins = new ArrayList<>();
        plugins.add(new AutomaticTaskPlugin());
        plugins.add(new PauseTaskPlugin());
        plugins.addAll(List.of(additionalPlugins));
        return new RegisteredTaskTypeDispatcher(
            new TaskPluginRegistry(plugins)
        );
    }
}
