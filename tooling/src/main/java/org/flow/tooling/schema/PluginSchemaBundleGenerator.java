package org.flow.tooling.schema;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.flow.core.docs.JsonSchemaGenerator;
import org.flow.core.docs.SchemaType;
import org.flow.core.models.flows.Flow;
import org.flow.core.models.tasks.Task;
import org.flow.core.models.triggers.AbstractTrigger;
import org.flow.core.plugins.PluginArtifact;
import org.flow.core.plugins.PluginRegistry;
import org.flow.core.plugins.RegisteredPlugin;
import org.flow.core.preview.FileRenderer;

import lombok.extern.slf4j.Slf4j;

/** 从上游 PluginsSchemaCommand 提取的离线聚合算法，保留四种根类型和插件目录协议。 */
@Slf4j
public class PluginSchemaBundleGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Extensions probed against {@code supports()} for renderers that declare none: file formats a
     * preview plausibly targets. Only a fallback — a renderer overriding
     * {@link FileRenderer#extensions()} is taken at its word, and an extension outside this list
     * has to be declared to be advertised.
     */
    private static final Set<String> PROBED_EXTENSIONS = Set.of(
        "avro", "parquet", "orc", "arrow", "feather",
        "csv", "tsv", "psv", "xlsx", "xls", "ods",
        "json", "jsonl", "ndjson", "ion", "xml", "yaml", "yml", "toml",
        "txt", "md", "log", "html", "pdf",
        "png", "jpg", "jpeg", "gif", "svg", "webp", "bmp"
    );

    /** SchemaType → root class for schema generation (mirrors JsonSchemaCache). */
    private static final Map<SchemaType, Class<?>> SCHEMA_CLASSES = Map.of(
        SchemaType.FLOW, Flow.class,
        SchemaType.TASK, Task.class,
        SchemaType.TRIGGER, AbstractTrigger.class
    );

    /**
     * 使用已加载的原生插件注册表生成聚合 Schema；任一根类型失败时中止，不写出残缺产物。
     * @param registry 已加载内置与外部插件的注册表，不能为 null
     * @param output 输出 JSON 文件，父目录不存在时创建，已有文件会覆盖
     * @throws IOException 创建目录或写入文件失败时抛出
     */
    public void write(PluginRegistry registry, File output) throws IOException {
        JsonSchemaGenerator generator = new JsonSchemaGenerator(java.util.Objects.requireNonNull(registry));

        Map<String, Object> definitions = new LinkedHashMap<>();
        Map<String, Object> roots = new LinkedHashMap<>();
        for (Map.Entry<SchemaType, Class<?>> entry : SCHEMA_CLASSES.entrySet()) {
            SchemaType schemaType = entry.getKey();
            Class<?> cls = entry.getValue();
            Map<String, Object> schema = generator.schemas(cls);
            mergeDefinitions(definitions, schema);
            roots.put(schemaType.name().toLowerCase(Locale.ROOT), schema.get("$ref"));
            log.debug("Generated schema for type '{}'", schemaType);
        }

        List<Map<String, Object>> plugins = catalogEntries(registry);
        Map<String, String> fileRenderers = fileRendererArtifacts(registry);

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("definitions", definitions);
        bundle.put("roots", roots);
        bundle.put("plugins", plugins);
        bundle.put("fileRenderers", fileRenderers);

        if (
            output.getParentFile() != null && !output.getParentFile().mkdirs()
                && !output.getParentFile().isDirectory()
        ) {
            throw new IOException("Failed to create output directory: " + output.getParentFile());
        }

        MAPPER.writeValue(output, bundle);
        log.info("Plugin schema bundle written to {} ({} roots, {} definitions, {} plugins, {} renderer extensions)",
            output.getAbsolutePath(), roots.size(), definitions.size(), plugins.size(), fileRenderers.size());
    }

    /**
     * Builds the bundle's local catalog: one entry per scanned plugin jar, carrying its Maven
     * coordinates and its Java package group, so an instance can resolve a type FQCN to an
     * artifact for plugins the hosted catalog does not list (private or in-house ones).
     * Plugins whose coordinates cannot be read from the jar file name are skipped.
     */
    private static List<Map<String, Object>> catalogEntries(final PluginRegistry registry) {
        if (registry == null) {
            return List.of();
        }

        return registry.plugins().stream()
            .filter(plugin -> plugin.group() != null)
            .map(plugin -> artifactOf(plugin).map(artifact ->
            {
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("title", plugin.title());
                entry.put("groupId", artifact.groupId());
                entry.put("artifactId", artifact.artifactId());
                entry.put("group", plugin.group());
                return entry;
            }))
            .flatMap(Optional::stream)
            .toList();
    }

    /**
     * Builds the extension → {@code groupId:artifactId} map of every {@link FileRenderer} declared
     * by a scanned plugin, so the file preview can fetch the plugin that renders an extension no
     * installed renderer supports.
     */
    private static Map<String, String> fileRendererArtifacts(final PluginRegistry registry) {
        if (registry == null) {
            return Map.of();
        }

        Map<String, String> byExtension = new TreeMap<>();
        for (RegisteredPlugin plugin : registry.plugins()) {
            Optional<PluginArtifact> artifact = artifactOf(plugin);
            if (artifact.isEmpty()) {
                continue;
            }
            String coordinates = artifact.get().groupId() + ":" + artifact.get().artifactId();
            for (Class<? extends FileRenderer> renderer : plugin.getFileRenderers()) {
                declaredExtensions(renderer).forEach(
                    extension -> byExtension.putIfAbsent(extension.toLowerCase(Locale.ROOT), coordinates)
                );
            }
        }
        return byExtension;
    }

    // Core renderers ship with the distribution, so only external plugins carry installable coordinates.
    private static Optional<PluginArtifact> artifactOf(final RegisteredPlugin plugin) {
        if (plugin.getExternalPlugin() == null || plugin.getExternalPlugin().getLocation() == null) {
            return Optional.empty();
        }

        String fileName = new File(plugin.getExternalPlugin().getLocation().getPath()).getName();
        try {
            return Optional.of(PluginArtifact.fromFileName(fileName));
        } catch (IllegalArgumentException e) {
            log.debug("Skipping plugin '{}': cannot read Maven coordinates from file name '{}'.", plugin.group(), fileName);
            return Optional.empty();
        }
    }

    /**
     * Extensions a renderer handles: what it declares through {@link FileRenderer#extensions()},
     * falling back to probing {@link FileRenderer#supports(String)} over
     * {@link #PROBED_EXTENSIONS} for renderers that predate the declaration.
     */
    private static Set<String> declaredExtensions(final Class<? extends FileRenderer> renderer) {
        try {
            FileRenderer instance = renderer.getDeclaredConstructor().newInstance();
            Set<String> declared = instance.extensions();
            if (!declared.isEmpty()) {
                return declared;
            }
            return PROBED_EXTENSIONS.stream().filter(instance::supports).collect(Collectors.toSet());
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.debug("Could not read the extensions of renderer '{}'", renderer.getName(), e);
            return Set.of();
        }
    }

    /** Copies {@code schema}'s {@code definitions} into the shared pool, keeping the first (identical) copy of any class already hoisted from another root. */
    @SuppressWarnings("unchecked")
    private static void mergeDefinitions(Map<String, Object> sharedDefinitions, Map<String, Object> schema) {
        if (schema.get("definitions") instanceof Map<?, ?> definitions) {
            ((Map<String, Object>) definitions).forEach(sharedDefinitions::putIfAbsent);
        }
    }

}
