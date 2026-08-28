package org.cses.flow.core.serializers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.FieldScope;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaGenerationContext;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import com.github.victools.jsonschema.module.swagger2.Swagger2Module;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.Plugin;
import org.cses.flow.core.plugins.PluginMetadata;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Lazily generates and caches the accepted definition schema for a plugin.
 */
@Singleton
public final class PluginSchemaGenerator {

    private static final String TYPE_PROPERTY = "type";
    private static final String KEY_PROPERTY = "key";
    private static final String SYSTEM_ID_PROPERTY = "id";
    private static final Set<String> DEFAULTED_TASK_PROPERTIES = Set.of(
        "inputs",
        "outputs",
        "tasks"
    );

    private final JacksonMapper jacksonMapper;
    private final ConcurrentMap<Class<? extends Plugin>, Map<String, Object>>
        cache = new ConcurrentHashMap<>();

    public PluginSchemaGenerator(JacksonMapper jacksonMapper) {
        this.jacksonMapper = jacksonMapper;
    }

    /**
     * Returns one immutable Draft 7 schema. Failed generations are not cached.
     */
    public Map<String, Object> generate(
        PluginMetadata<? extends Plugin> metadata
    ) {
        return cache.computeIfAbsent(
            metadata.type(),
            ignored -> generateUncached(metadata)
        );
    }

    private Map<String, Object> generateUncached(
        PluginMetadata<? extends Plugin> metadata
    ) {
        Object defaultInstance = newDefaultInstance(metadata.type());
        SchemaGeneratorConfigBuilder builder =
            new SchemaGeneratorConfigBuilder(
                jacksonMapper.jsonMapper().copy(),
                SchemaVersion.DRAFT_7,
                OptionPreset.PLAIN_JSON
            )
                .with(new JacksonModule())
                .with(new JakartaValidationModule(
                    JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED,
                    JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS
                ))
                .with(new Swagger2Module())
                .with(
                    Option.NONPUBLIC_NONSTATIC_FIELDS_WITHOUT_GETTERS,
                    Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT,
                    Option.SCHEMA_VERSION_INDICATOR
                );
        builder.forFields()
            .withIgnoreCheck(PluginSchemaGenerator::isSystemId)
            .withDefaultResolver(field -> defaultValue(
                defaultInstance,
                field
            ))
            .withInstanceAttributeOverride(
                PluginSchemaGenerator::addSwaggerExamples
            );

        ObjectNode schema = new SchemaGenerator(builder.build())
            .generateSchema(metadata.type());
        normalizeDefinitionSchema(schema, metadata.canonicalType());
        return immutableMap(jacksonMapper.toMap(schema));
    }

    private static boolean isSystemId(FieldScope field) {
        return SYSTEM_ID_PROPERTY.equals(field.getName())
            && field.getRawMember().getDeclaringClass() == Task.class;
    }

    private static Object newDefaultInstance(
        Class<? extends Plugin> pluginClass
    ) {
        try {
            return pluginClass.getConstructor().newInstance();
        } catch (NoSuchMethodException
                 | InstantiationException
                 | IllegalAccessException
                 | InvocationTargetException exception) {
            throw new IllegalArgumentException(
                "Unable to create default plugin instance: "
                    + pluginClass.getName(),
                exception
            );
        }
    }

    private static Object defaultValue(
        Object defaultInstance,
        FieldScope fieldScope
    ) {
        Field field = fieldScope.getRawMember();
        if (!field.getDeclaringClass().isInstance(defaultInstance)) {
            return null;
        }
        try {
            if (!field.trySetAccessible()) {
                return null;
            }
            return field.get(defaultInstance);
        } catch (IllegalAccessException exception) {
            return null;
        }
    }

    private static void addSwaggerExamples(
        ObjectNode attributes,
        FieldScope field,
        SchemaGenerationContext context
    ) {
        Schema annotation = field.getAnnotationConsideringFieldAndGetter(
            Schema.class
        );
        if (annotation == null) {
            return;
        }
        LinkedHashSet<String> examples = new LinkedHashSet<>();
        if (!annotation.example().isBlank()) {
            examples.add(annotation.example());
        }
        for (String example : annotation.examples()) {
            if (example != null && !example.isBlank()) {
                examples.add(example);
            }
        }
        if (examples.isEmpty()) {
            return;
        }
        ArrayNode values = attributes.putArray("examples");
        examples.stream()
            .map(example -> exampleNode(example, field, context))
            .forEach(values::add);
    }

    private static JsonNode exampleNode(
        String example,
        FieldScope field,
        SchemaGenerationContext context
    ) {
        Class<?> fieldType = field.getDeclaredType().getErasedType();
        if (CharSequence.class.isAssignableFrom(fieldType)
            || fieldType == Character.class
            || fieldType == char.class
            || fieldType.isEnum()) {
            return context.getGeneratorConfig().getObjectMapper()
                .getNodeFactory().textNode(example);
        }
        try {
            return context.getGeneratorConfig().getObjectMapper()
                .readTree(example);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return context.getGeneratorConfig().getObjectMapper()
                .getNodeFactory().textNode(example);
        }
    }

    private static void normalizeDefinitionSchema(
        ObjectNode schema,
        String canonicalType
    ) {
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.withObject("properties");
        properties.remove(SYSTEM_ID_PROPERTY);
        ObjectNode type = properties.withObject(TYPE_PROPERTY);
        type.removeAll();
        type.put("type", "string");
        type.put("const", canonicalType);

        removeDefaultedRequirements(schema);
        ArrayNode required = schema.withArray("required");
        removeRequired(required, SYSTEM_ID_PROPERTY);
        DEFAULTED_TASK_PROPERTIES.forEach(property ->
            removeRequired(required, property)
        );
        addRequired(required, TYPE_PROPERTY);
        addRequired(required, KEY_PROPERTY);
    }

    private static void removeDefaultedRequirements(
        JsonNode node
    ) {
        if (node instanceof ObjectNode object) {
            if (object.get("properties") instanceof ObjectNode properties) {
                if (object.get("required") instanceof ArrayNode required) {
                    List<String> removable = new ArrayList<>();
                    required.forEach(value -> {
                        String propertyName = value.asText();
                        JsonNode property = properties.get(propertyName);
                        if (property != null && property.has("default")) {
                            removable.add(propertyName);
                        }
                    });
                    removable.forEach(propertyName ->
                        removeRequired(required, propertyName)
                    );
                    if (required.isEmpty()) {
                        object.remove("required");
                    }
                }
            }
            object.elements().forEachRemaining(
                PluginSchemaGenerator::removeDefaultedRequirements
            );
        } else if (node instanceof ArrayNode array) {
            array.forEach(
                PluginSchemaGenerator::removeDefaultedRequirements
            );
        }
    }

    private static void addRequired(ArrayNode required, String property) {
        for (JsonNode current : required) {
            if (property.equals(current.asText())) {
                return;
            }
        }
        required.add(property);
    }

    private static void removeRequired(
        ArrayNode required,
        String property
    ) {
        for (int index = required.size() - 1; index >= 0; index--) {
            if (property.equals(required.get(index).asText())) {
                required.remove(index);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> immutableMap(
        Map<String, Object> source
    ) {
        return (Map<String, Object>) immutableValue(source);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> immutable = new LinkedHashMap<>();
            map.forEach((key, nested) -> immutable.put(
                String.valueOf(key),
                immutableValue(nested)
            ));
            return Collections.unmodifiableMap(immutable);
        }
        if (value instanceof List<?> list) {
            return list.stream()
                .map(PluginSchemaGenerator::immutableValue)
                .toList();
        }
        return value;
    }
}
