package org.cses.flow.controller.plugins;

import io.micronaut.serde.annotation.Serdeable;
import org.cses.flow.core.plugins.PluginMetadata;
import org.cses.flow.core.plugins.RegisteredPlugin;
import org.cses.flow.core.services.plugins.PluginDetails;

import java.util.List;
import java.util.Map;

/**
 * HTTP representations of the plugin catalog without exposing Java classes.
 */
public final class PluginModels {

    private PluginModels() {
    }

    @Serdeable
    public record PluginMetadataView(
        String type,
        String baseType,
        String title,
        String description
    ) {

        static PluginMetadataView from(PluginMetadata<?> metadata) {
            return new PluginMetadataView(
                metadata.canonicalType(),
                metadata.baseClass().getCanonicalName(),
                metadata.title(),
                metadata.description()
            );
        }
    }

    @Serdeable
    public record RegisteredPluginView(
        String name,
        String title,
        String description,
        List<PluginMetadataView> tasks
    ) {

        static RegisteredPluginView from(RegisteredPlugin plugin) {
            return new RegisteredPluginView(
                plugin.name(),
                plugin.title(),
                plugin.description(),
                plugin.tasks().stream()
                    .map(PluginMetadataView::from)
                    .toList()
            );
        }
    }

    @Serdeable
    public record PluginDetailsView(
        PluginMetadataView metadata,
        Map<String, Object> schema
    ) {

        static PluginDetailsView from(PluginDetails details) {
            return new PluginDetailsView(
                PluginMetadataView.from(details.metadata()),
                details.schema()
            );
        }
    }
}
