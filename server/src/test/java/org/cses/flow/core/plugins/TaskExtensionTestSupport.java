package org.cses.flow.core.plugins;

import org.cses.flow.extensions.tasks.AutomaticTaskPlugin;
import org.cses.flow.extensions.tasks.ParallelTaskPlugin;
import org.cses.flow.extensions.tasks.PauseTaskPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the production Task extension chain without a Micronaut context.
 */
public final class TaskExtensionTestSupport {

    private TaskExtensionTestSupport() {
    }

    public static TaskTypeDispatcher builtInDispatcher() {
        return withPlugins();
    }

    public static TaskTypeDispatcher withPlugins(
        TaskExtension... additionalPlugins
    ) {
        List<Plugin> plugins = new ArrayList<>();
        plugins.add(new AutomaticTaskPlugin());
        plugins.add(new PauseTaskPlugin());
        plugins.add(new ParallelTaskPlugin());
        plugins.addAll(List.of(additionalPlugins));
        return new RegisteredTaskTypeDispatcher(
            new PluginRegistry(plugins)
        );
    }
}
