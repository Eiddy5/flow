package org.cses.flow.core.services.plugins;

import org.cses.flow.core.plugins.Plugin;
import org.cses.flow.core.plugins.PluginExample;
import org.cses.flow.core.plugins.PluginMetadata;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Query result containing plugin metadata, usage examples, and its
 * definition schema.
 */
public record PluginDetails(
    PluginMetadata<? extends Plugin> metadata,
    Map<String, Object> schema
) {

    public PluginDetails {
        Objects.requireNonNull(metadata, "Plugin metadata");
        Objects.requireNonNull(schema, "Plugin schema");
    }

    public List<PluginExample> examples() {
        return metadata.examples();
    }
}
