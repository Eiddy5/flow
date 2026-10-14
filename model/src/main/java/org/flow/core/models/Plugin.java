package org.flow.core.models;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.flow.core.models.annotations.Plugin.Id;

import jakarta.validation.constraints.NotNull;

/**
 * Top-level interface for the Flow plugins.
 */
public interface Plugin {

    /**
     * Gets the type of this plugin.
     *
     * @return the string type of the plugin.
     */
    @NotNull
    default String getType() {
        return this.getClass().getCanonicalName();
    }

    /**
     * Static helper method to get the aliases of a given plugin.
     *
     * @param plugin The plugin type.
     * @return {@code true} if the plugin is internal.
     */
    static Set<String> getAliases(final Class<?> plugin) {
        org.flow.core.models.annotations.Plugin annotation = plugin.getAnnotation(org.flow.core.models.annotations.Plugin.class);
        return Optional.ofNullable(annotation)
            .map(org.flow.core.models.annotations.Plugin::aliases)
            .stream()
            .flatMap(Arrays::stream)
            .collect(Collectors.toSet());
    }

    /**
     * Static helper method to check whether a given plugin is internal.
     *
     * @param plugin The plugin type.
     * @return {@code true} if the plugin is internal.
     */
    static boolean isInternal(final Class<?> plugin) {
        Objects.requireNonNull(plugin, "Cannot check if a plugin is internal from null");
        org.flow.core.models.annotations.Plugin annotation = plugin.getAnnotation(org.flow.core.models.annotations.Plugin.class);
        return Optional.ofNullable(annotation)
            .map(org.flow.core.models.annotations.Plugin::internal)
            .orElse(false);
    }

    /**
     * Static helper method to check whether a given plugin is deprecated.
     *
     * @param plugin The plugin type.
     * @return {@code true} if the plugin is deprecated.
     */
    static boolean isDeprecated(final Class<?> plugin) {
        Objects.requireNonNull(plugin, "Cannot check if a plugin is deprecated from null");
        Deprecated annotation = plugin.getAnnotation(Deprecated.class);
        return annotation != null;
    }

    /**
     * Static helper method to check whether a given plugin has PRIMARY priority.
     *
     * @param plugin The plugin class.
     * @return {@code true} if the plugin is annotated with {@link Plugin} and its priority is PRIMARY.
     */
    static boolean isPrimary(final Class<?> plugin) {
        Objects.requireNonNull(plugin, "Cannot check priority on null class");
        org.flow.core.models.annotations.Plugin annotation = plugin.getAnnotation(org.flow.core.models.annotations.Plugin.class);
        return Optional.ofNullable(annotation)
            .map(a -> a.priority() == org.flow.core.models.annotations.Plugin.Priority.PRIMARY)
            .orElse(false);
    }

    /**
     * Static helper method to get the id of a plugin.
     *
     * @param plugin The plugin type.
     * @return an optional string id.
     */
    static Optional<String> getId(final Class<?> plugin) {
        Objects.requireNonNull(plugin, "Cannot get plugin id from null");
        Id annotation = plugin.getAnnotation(Id.class);
        return Optional.ofNullable(annotation).map(Id::value).map(String::toLowerCase);
    }
}
