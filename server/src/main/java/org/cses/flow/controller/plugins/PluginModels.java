package org.cses.flow.controller.plugins;

import lombok.Getter;
import lombok.Setter;
import org.cses.flow.core.plugins.PluginMetadata;
import org.cses.flow.core.plugins.RegisteredPlugin;
import org.cses.flow.core.services.plugins.PluginDetails;
import org.paas.json.SerializableObject;

import java.util.List;
import java.util.Map;

/**
 * HTTP representations of the package-grouped plugin catalog without
 * exposing Java classes.
 */
public final class PluginModels {

    private PluginModels() {
    }

    @Getter
    @Setter
    public static final class PluginMetadataView
        extends SerializableObject {

        private String packageName;
        private String type;
        private String baseType;
        private String title;
        private String description;

        public PluginMetadataView() {
        }

        public PluginMetadataView(
            String packageName,
            String type,
            String baseType,
            String title,
            String description
        ) {
            this.packageName = packageName;
            this.type = type;
            this.baseType = baseType;
            this.title = title;
            this.description = description;
        }

        public String packageName() {
            return packageName;
        }

        public String type() {
            return type;
        }

        public String baseType() {
            return baseType;
        }

        public String title() {
            return title;
        }

        public String description() {
            return description;
        }

        static PluginMetadataView from(PluginMetadata<?> metadata) {
            return new PluginMetadataView(
                metadata.packageName(),
                metadata.canonicalType(),
                metadata.baseClass().getCanonicalName(),
                metadata.title(),
                metadata.description()
            );
        }
    }

    @Getter
    @Setter
    public static final class RegisteredPluginView
        extends SerializableObject {

        private String packageName;
        private List<PluginMetadataView> tasks;

        public RegisteredPluginView() {
        }

        public RegisteredPluginView(
            String packageName,
            List<PluginMetadataView> tasks
        ) {
            this.packageName = packageName;
            this.tasks = tasks;
        }

        public String packageName() {
            return packageName;
        }

        public List<PluginMetadataView> tasks() {
            return tasks;
        }

        static RegisteredPluginView from(RegisteredPlugin plugin) {
            return new RegisteredPluginView(
                plugin.packageName(),
                plugin.tasks().stream()
                    .map(PluginMetadataView::from)
                    .toList()
            );
        }
    }

    @Getter
    @Setter
    public static final class PluginDetailsView
        extends SerializableObject {

        private PluginMetadataView metadata;
        private Map<String, Object> schema;

        public PluginDetailsView() {
        }

        public PluginDetailsView(
            PluginMetadataView metadata,
            Map<String, Object> schema
        ) {
            this.metadata = metadata;
            this.schema = schema;
        }

        public PluginMetadataView metadata() {
            return metadata;
        }

        public Map<String, Object> schema() {
            return schema;
        }

        static PluginDetailsView from(PluginDetails details) {
            return new PluginDetailsView(
                PluginMetadataView.from(details.metadata()),
                details.schema()
            );
        }
    }
}
