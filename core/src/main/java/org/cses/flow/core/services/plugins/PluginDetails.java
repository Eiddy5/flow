package org.cses.flow.core.services.plugins;

import org.cses.flow.core.plugins.Plugin;
import org.cses.flow.core.plugins.PluginMetadata;

import java.util.Map;
import java.util.Objects;

/**
 * Query result containing plugin metadata and its definition schema.
 */
public record PluginDetails(
    PluginMetadata<? extends Plugin> metadata,
    Map<String, Object> schema
) {

    public PluginDetails {
        Objects.requireNonNull(metadata, "Plugin metadata");
        Objects.requireNonNull(schema, "Plugin schema");
    }
}
