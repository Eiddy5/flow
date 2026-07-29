package org.cses.flow.extensions.tasks;

import io.micronaut.context.annotation.Context;
import org.cses.flow.core.domains.tasks.TaskPlugin;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves Task plugins by their unique, normalized type.
 */
@Context
public final class TaskPluginRegistry {

    private final Map<String, TaskPlugin> pluginsByType;

    public TaskPluginRegistry(Collection<TaskPlugin> plugins) {
        if (plugins == null) {
            throw new IllegalStateException(
                "Task plugin registrations must not be null"
            );
        }

        Map<String, TaskPlugin> registrations = new LinkedHashMap<>();
        for (TaskPlugin plugin : plugins) {
            if (plugin == null) {
                throw new IllegalStateException(
                    "Task plugin registration must not be null"
                );
            }
            String type = normalize(plugin.type());
            if (type.isEmpty()) {
                throw new IllegalStateException(
                    "Task plugin type must not be blank: "
                        + plugin.getClass().getName()
                );
            }
            TaskPlugin existing = registrations.putIfAbsent(type, plugin);
            if (existing != null) {
                throw new IllegalStateException(
                    "Duplicate Task plugin type '"
                        + type
                        + "': "
                        + existing.getClass().getName()
                        + " and "
                        + plugin.getClass().getName()
                );
            }
        }
        this.pluginsByType = Collections.unmodifiableMap(registrations);
    }

    public Optional<TaskPlugin> find(String type) {
        return Optional.ofNullable(pluginsByType.get(normalize(type)));
    }

    private static String normalize(String type) {
        return type == null
            ? ""
            : type.trim().toUpperCase(Locale.ROOT);
    }
}
