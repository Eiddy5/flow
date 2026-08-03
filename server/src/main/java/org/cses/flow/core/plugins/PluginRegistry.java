package org.cses.flow.core.plugins;

import io.micronaut.context.annotation.Context;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable registry for every discovered plugin extension point.
 */
@Context
public final class PluginRegistry {

    private final Map<
        Class<? extends Plugin>,
        Map<String, Plugin>
    > pluginsByExtensionPoint;

    public PluginRegistry(Collection<Plugin> plugins) {
        this(plugins, PluginLoader.load(Plugin.class));
    }

    PluginRegistry(
        Collection<Plugin> plugins,
        ClassLoader classLoader
    ) {
        this(plugins, PluginLoader.load(Plugin.class, classLoader));
    }

    private PluginRegistry(
        Collection<Plugin> plugins,
        List<Plugin> classpathPlugins
    ) {
        if (plugins == null) {
            throw new IllegalStateException(
                "Plugin registrations must not be null"
            );
        }

        ArrayList<Plugin> discovered = new ArrayList<>(plugins);
        discovered.addAll(classpathPlugins);
        Map<
            Class<? extends Plugin>,
            Map<String, Plugin>
        > registrations = new LinkedHashMap<>();
        for (Plugin plugin : discovered) {
            register(registrations, plugin);
        }

        LinkedHashMap<
            Class<? extends Plugin>,
            Map<String, Plugin>
        > immutable = new LinkedHashMap<>();
        registrations.forEach((extensionPoint, pluginsByType) ->
            immutable.put(
                extensionPoint,
                Collections.unmodifiableMap(
                    new LinkedHashMap<>(pluginsByType)
                )
            )
        );
        this.pluginsByExtensionPoint = Collections.unmodifiableMap(immutable);
    }

    public <P extends Plugin> Optional<P> find(
        Class<P> extensionPoint,
        String type
    ) {
        Objects.requireNonNull(extensionPoint, "Plugin extension point");
        Plugin plugin = pluginsByExtensionPoint
            .getOrDefault(extensionPoint, Map.of())
            .get(normalize(type));
        return Optional.ofNullable(plugin).map(extensionPoint::cast);
    }

    private static void register(
        Map<Class<? extends Plugin>, Map<String, Plugin>> registrations,
        Plugin plugin
    ) {
        if (plugin == null) {
            throw new IllegalStateException(
                "Plugin registration must not be null"
            );
        }
        Class<? extends Plugin> extensionPoint = Objects.requireNonNull(
            plugin.extensionPoint(),
            "Plugin extension point"
        );
        if (!extensionPoint.isInstance(plugin)) {
            throw new IllegalStateException(
                "Plugin "
                    + plugin.getClass().getName()
                    + " does not implement extension point "
                    + extensionPoint.getName()
            );
        }
        String type = normalize(plugin.type());
        if (type.isEmpty()) {
            throw new IllegalStateException(
                "Plugin type must not be blank: "
                    + plugin.getClass().getName()
            );
        }

        Map<String, Plugin> pluginsByType = registrations.computeIfAbsent(
            extensionPoint,
            ignored -> new LinkedHashMap<>()
        );
        Plugin existing = pluginsByType.putIfAbsent(type, plugin);
        if (existing != null) {
            throw new IllegalStateException(
                "Duplicate plugin type '"
                    + type
                    + "' for extension point '"
                    + extensionPoint.getName()
                    + "': "
                    + existing.getClass().getName()
                    + " and "
                    + plugin.getClass().getName()
            );
        }
    }

    private static String normalize(String type) {
        return type == null
            ? ""
            : type.trim().toUpperCase(Locale.ROOT);
    }
}
