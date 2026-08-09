package org.cses.flow.core.plugins;

import io.micronaut.context.annotation.Context;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable registry assembled from Micronaut-discovered plugin classes on
 * the application classpath and grouped by their real Java packages.
 */
@Context
public final class DefaultPluginRegistry implements PluginRegistry {

    private final List<RegisteredPlugin> registeredPlugins;
    private final Map<String, PluginMetadata<? extends Plugin>>
        metadataByType;

    public DefaultPluginRegistry(Collection<Plugin> plugins) {
        if (plugins == null) {
            throw new IllegalStateException(
                "Plugin registrations must not be null"
            );
        }

        List<Plugin> orderedPlugins = new ArrayList<>();
        for (Plugin plugin : plugins) {
            if (plugin == null) {
                throw new IllegalStateException(
                    "Plugin registration must not be null"
                );
            }
            orderedPlugins.add(plugin);
        }
        orderedPlugins.sort(Comparator.comparing(
            DefaultPluginRegistry::orderingName
        ));

        Map<String, PluginMetadata<? extends Plugin>> registrations =
            new LinkedHashMap<>();
        Map<String, List<PluginMetadata<Task>>> tasksByPackage =
            new LinkedHashMap<>();
        orderedPlugins.forEach(plugin -> register(
            registrations,
            tasksByPackage,
            plugin
        ));

        this.metadataByType = Map.copyOf(registrations);
        this.registeredPlugins = tasksByPackage.entrySet().stream()
            .sorted(Comparator.comparing(Map.Entry::getKey))
            .map(entry -> new RegisteredPlugin(
                entry.getKey(),
                entry.getValue()
            ))
            .toList();
    }

    @Override
    public List<RegisteredPlugin> plugins() {
        return registeredPlugins;
    }

    @Override
    public Optional<PluginMetadata<? extends Plugin>> findMetadata(
        String type
    ) {
        if (type == null || type.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(metadataByType.get(type));
    }

    @Override
    public <P extends Plugin> Class<? extends P> resolve(
        String type,
        Class<P> expectedBaseClass
    ) {
        Objects.requireNonNull(
            expectedBaseClass,
            "Expected plugin base class"
        );
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException(
                "Plugin type must not be blank"
            );
        }
        PluginMetadata<? extends Plugin> metadata =
            metadataByType.get(type);
        if (metadata == null) {
            throw new IllegalArgumentException(
                "No plugin registered for type: " + type
            );
        }
        Class<? extends Plugin> pluginClass = metadata.type();
        if (!expectedBaseClass.isAssignableFrom(pluginClass)) {
            throw new IllegalArgumentException(
                "Plugin " + type + " is not a "
                    + expectedBaseClass.getName()
            );
        }
        return pluginClass.asSubclass(expectedBaseClass);
    }

    private static void register(
        Map<String, PluginMetadata<? extends Plugin>> registrations,
        Map<String, List<PluginMetadata<Task>>> tasksByPackage,
        Plugin plugin
    ) {
        Class<? extends Plugin> pluginClass = plugin.getClass()
            .asSubclass(Plugin.class);
        org.cses.flow.core.plugins.annotations.Plugin annotation =
            pluginClass.getDeclaredAnnotation(
                org.cses.flow.core.plugins.annotations.Plugin.class
            );
        if (annotation == null) {
            throw new IllegalStateException(
                "Plugin is missing @Plugin: " + pluginClass.getName()
            );
        }
        if (!Modifier.isPublic(pluginClass.getModifiers())
            || Modifier.isAbstract(pluginClass.getModifiers())) {
            throw new IllegalStateException(
                "Plugin must be a public concrete class: "
                    + pluginClass.getName()
            );
        }
        requirePublicNoArgsConstructor(pluginClass);
        if (!Task.class.isAssignableFrom(pluginClass)) {
            throw new IllegalStateException(
                "Unsupported plugin capability: " + pluginClass.getName()
            );
        }
        requireTaskCapability(pluginClass);

        String canonicalName = pluginClass.getCanonicalName();
        if (canonicalName == null || canonicalName.isBlank()) {
            throw new IllegalStateException(
                "Plugin must have a canonical class name: "
                    + pluginClass.getName()
            );
        }
        if (!canonicalName.equals(plugin.getType())) {
            throw new IllegalStateException(
                "Plugin type must equal its canonical class name: "
                    + pluginClass.getName()
            );
        }
        String packageName = pluginClass.getPackageName();
        if (packageName.isBlank()) {
            throw new IllegalStateException(
                "Plugin must belong to a named Java package: "
                    + pluginClass.getName()
            );
        }

        Class<? extends Task> taskClass = pluginClass.asSubclass(Task.class);
        PluginMetadata<Task> metadata = new PluginMetadata<>(
            taskClass,
            Task.class,
            annotation.title(),
            annotation.description()
        );
        PluginMetadata<? extends Plugin> existing =
            registrations.putIfAbsent(canonicalName, metadata);
        if (existing != null) {
            throw new IllegalStateException(
                "Duplicate plugin type '" + canonicalName + "': "
                    + existing.type().getName() + " and "
                    + pluginClass.getName()
            );
        }
        tasksByPackage.computeIfAbsent(
            packageName,
            ignored -> new ArrayList<>()
        ).add(metadata);
    }

    private static String orderingName(Plugin plugin) {
        String canonicalName = plugin.getClass().getCanonicalName();
        return canonicalName == null
            ? plugin.getClass().getName()
            : canonicalName;
    }

    private static void requirePublicNoArgsConstructor(
        Class<? extends Plugin> pluginClass
    ) {
        try {
            Constructor<?> constructor = pluginClass.getConstructor();
            if (!Modifier.isPublic(constructor.getModifiers())) {
                throw new NoSuchMethodException();
            }
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(
                "Plugin requires a public no-args constructor: "
                    + pluginClass.getName(),
                exception
            );
        }
    }

    private static void requireTaskCapability(
        Class<? extends Plugin> pluginClass
    ) {
        boolean runnable = RunnableTask.class.isAssignableFrom(pluginClass);
        boolean orchestration = OrchestrationTask.class.isAssignableFrom(
            pluginClass
        );
        if (runnable == orchestration) {
            throw new IllegalStateException(
                "Task plugin must implement exactly one runtime capability: "
                    + pluginClass.getName()
            );
        }
    }
}
