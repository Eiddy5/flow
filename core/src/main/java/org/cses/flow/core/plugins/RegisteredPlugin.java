package org.cses.flow.core.plugins;

import org.cses.flow.core.domains.tasks.Task;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * All registered plugin capabilities declared in one real Java package.
 */
public record RegisteredPlugin(
    String packageName,
    List<PluginMetadata<Task>> tasks
) {

    public static RegisteredPlugin from(
        String packageName,
        List<PluginMetadata<Task>> tasks
    ) {
        return new RegisteredPlugin(packageName, tasks);
    }

    public RegisteredPlugin {
        if (packageName == null || packageName.isBlank()) {
            throw new IllegalArgumentException(
                "Registered plugin package name must not be blank"
            );
        }
        Objects.requireNonNull(tasks, "Registered plugin tasks");
        if (tasks.stream().anyMatch(task ->
            !packageName.equals(task.packageName())
        )) {
            throw new IllegalArgumentException(
                "Registered plugin tasks must belong to package: "
                    + packageName
            );
        }
        tasks = tasks.stream()
            .sorted(Comparator.comparing(PluginMetadata::canonicalType))
            .toList();
    }
}
