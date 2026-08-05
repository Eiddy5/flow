package org.cses.flow.core.plugins;

import org.cses.flow.core.domains.tasks.Task;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * One registered plugin bundle and all capabilities loaded from it.
 */
public record RegisteredPlugin(
    String name,
    String title,
    String description,
    List<PluginMetadata<Task>> tasks
) {

    public static final String CORE_NAME = "core";

    public RegisteredPlugin {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                "Registered plugin name must not be blank"
            );
        }
        title = title == null || title.isBlank() ? name : title;
        description = description == null || description.isBlank()
            ? ""
            : description;
        Objects.requireNonNull(tasks, "Registered plugin tasks");
        tasks = tasks.stream()
            .sorted(Comparator.comparing(PluginMetadata::canonicalType))
            .toList();
    }

    public static RegisteredPlugin core(
        List<PluginMetadata<Task>> tasks
    ) {
        return new RegisteredPlugin(
            CORE_NAME,
            "Flow Core",
            "",
            tasks
        );
    }
}
