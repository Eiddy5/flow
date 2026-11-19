package org.flow.http.plugins;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.inject.Singleton;
import org.flow.core.docs.JsonSchemaGenerator;
import org.flow.core.services.PluginService;

/** Builds HTTP representations of entries from the engine's existing plugin registry. */
@Singleton
final class PluginQueries {
    private final PluginService plugins;
    private final JsonSchemaGenerator schemas;

    PluginQueries(PluginService plugins, JsonSchemaGenerator schemas) {
        this.plugins = plugins;
        this.schemas = schemas;
    }

    List<PluginModels.Metadata> list(String category) {
        Map<String, CatalogEntry> types = new TreeMap<>();
        entries().forEach(entry -> types.merge(entry.type().getName(), entry, (first, second) ->
            registeredType(second.type().getName()).filter(second.type()::equals).isPresent() ? second : first
        ));
        return types.values().stream()
            .filter(entry -> category == null || category.equals(entry.category()))
            .map(CatalogEntry::metadata)
            .toList();
    }

    Optional<PluginModels.Details> detail(String type) {
        return registeredType(type)
            .flatMap(registered -> entries().filter(entry -> entry.type().equals(registered)).findFirst())
            .map(this::detail);
    }

    Optional<List<String>> versions(String type) {
        return registeredType(type).map(registered -> plugins.versions(registered.getName()));
    }

    private Optional<Class<?>> registeredType(String type) {
        return plugins.plugin(type)
            .<Class<?>>map(metadata -> metadata.type())
            .filter(registered -> registered.getName().equals(type));
    }

    private Stream<CatalogEntry> entries() {
        return plugins.plugins().stream().flatMap(bundle -> bundle.allClassGrouped().entrySet().stream()
            .flatMap(category -> category.getValue().stream()
                .map(type -> new CatalogEntry(type, category.getKey(), bundle.group()))));
    }

    private PluginModels.Details detail(CatalogEntry entry) {
        Map<String, Object> schema = schemas.properties(null, entry.type());
        schema.putIfAbsent("type", "object");
        List<PluginModels.Output> outputs = schemas.outputProperties(entry.type()).entrySet().stream()
            .map(property -> {
                Map<?, ?> attributes = (Map<?, ?>) property.getValue();
                String description = text(attributes.get("description"));
                return new PluginModels.Output(property.getKey(), text(attributes.get("type")),
                    description != null ? description : text(attributes.get("title")));
            })
            .toList();
        return new PluginModels.Details(entry.metadata(), schema, examples(entry.type()), outputs);
    }

    private static List<PluginModels.Example> examples(Class<?> type) {
        var annotation = type.getAnnotation(org.flow.plugin.api.annotations.Plugin.class);
        if (annotation == null) {
            return List.of();
        }
        return Arrays.stream(annotation.examples())
            .map(example -> new PluginModels.Example(example.title(), example.lang(), example.full(),
                List.of(String.join("\n", example.code()).split("\\R", -1))))
            .toList();
    }

    private static String text(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private record CatalogEntry(Class<?> type, String category, String group) {
        PluginModels.Metadata metadata() {
            Schema schema = type.getAnnotation(Schema.class);
            String title = schema == null ? null : text(schema.title());
            String description = schema == null ? null : text(schema.description());
            return new PluginModels.Metadata(type.getName(), category,
                title != null ? title : type.getSimpleName(), description, group);
        }
    }
}
