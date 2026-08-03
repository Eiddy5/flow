package org.cses.flow.core.plugins;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Discovers Java SPI providers from the application classpath.
 */
public final class PluginLoader {

    private PluginLoader() {
    }

    public static <T> List<T> load(Class<T> pluginType) {
        Objects.requireNonNull(pluginType, "Plugin type");
        return load(pluginType, contextClassLoader(pluginType));
    }

    public static <T> List<T> load(
        Class<T> pluginType,
        ClassLoader classLoader
    ) {
        Objects.requireNonNull(pluginType, "Plugin type");
        Objects.requireNonNull(classLoader, "Plugin ClassLoader");

        ArrayList<T> providers = new ArrayList<>();
        try {
            ServiceLoader.load(pluginType, classLoader)
                .forEach(providers::add);
        } catch (ServiceConfigurationError error) {
            throw new IllegalStateException(
                "Failed to load classpath plugins for "
                    + pluginType.getName(),
                error
            );
        }
        return List.copyOf(providers);
    }

    private static ClassLoader contextClassLoader(Class<?> pluginType) {
        ClassLoader classLoader = Thread.currentThread()
            .getContextClassLoader();
        return classLoader == null
            ? pluginType.getClassLoader()
            : classLoader;
    }
}
