package org.cses.flow.core.services.plugins;

import jakarta.inject.Singleton;
import org.cses.flow.core.plugins.Plugin;
import org.cses.flow.core.plugins.PluginMetadata;
import org.cses.flow.core.plugins.PluginRegistry;
import org.cses.flow.core.plugins.RegisteredPlugin;
import org.cses.flow.core.serializers.PluginSchemaGenerator;

import java.util.List;

/**
 * Public read-only entry point for the global plugin catalog.
 */
@Singleton
public final class PluginService {

    private final PluginRegistry pluginRegistry;
    private final PluginSchemaGenerator schemaGenerator;

    public PluginService(
        PluginRegistry pluginRegistry,
        PluginSchemaGenerator schemaGenerator
    ) {
        this.pluginRegistry = pluginRegistry;
        this.schemaGenerator = schemaGenerator;
    }

    public List<RegisteredPlugin> plugins() {
        return pluginRegistry.plugins();
    }

    public PluginDetails plugin(String canonicalType) {
        PluginMetadata<? extends Plugin> metadata = pluginRegistry
            .findMetadata(canonicalType)
            .orElseThrow(() -> new IllegalArgumentException(
                "No plugin registered for type: " + canonicalType
            ));
        return new PluginDetails(
            metadata,
            schemaGenerator.generate(metadata)
        );
    }
}
