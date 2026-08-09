package org.cses.flow.core.serializers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.PluginRegistry;
import org.paas.common.util.StringUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Materializes one deployed Flow from strict YAML and registered Task plugins.
 */
@Singleton
public final class FlowDefinitionDeserializer {

    private static final Set<String> FLOW_FIELDS = Set.of(
        "key",
        "description",
        "inputs",
        "outputs",
        "tasks"
    );
    private static final Set<String> TASK_SYSTEM_FIELDS = Set.of(
        "id",
        "parentId",
        "taskId"
    );

    private final YamlParser yamlParser;
    private final JacksonMapper jacksonMapper;
    private final PluginRegistry pluginRegistry;

    public FlowDefinitionDeserializer(
        YamlParser yamlParser,
        JacksonMapper jacksonMapper,
        PluginRegistry pluginRegistry
    ) {
        this.yamlParser = yamlParser;
        this.jacksonMapper = jacksonMapper;
        this.pluginRegistry = pluginRegistry;
    }

    public Flow deserialize(
        String source,
        String companyId,
        String flowId,
        Flow latest,
        ActorRef actor,
        long deployedAt
    ) {
        ObjectNode definition = yamlParser.parseTree(source);
        rejectUnknownFields(definition, FLOW_FIELDS, "Flow");

        Map<String, String> taskIdsByKey = new LinkedHashMap<>();
        if (latest != null) {
            latest.allTasks().forEach(task ->
                taskIdsByKey.put(task.key(), task.id())
            );
        }
        return Flow.deploy(
            companyId,
            flowId,
            requiredText(definition, "key", "Flow"),
            optionalText(definition, "description", "", "Flow"),
            inputs(definition.get("inputs"), "Flow.inputs"),
            outputs(definition.get("outputs"), "Flow.outputs"),
            tasks(
                definition.get("tasks"),
                "Flow.tasks",
                taskIdsByKey
            ),
            latest,
            actor,
            deployedAt
        );
    }

    private List<Task> tasks(
        JsonNode value,
        String path,
        Map<String, String> idsByKey
    ) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof ArrayNode definitions)) {
            throw new IllegalArgumentException(path + " must be a list");
        }
        List<Task> tasks = new ArrayList<>(definitions.size());
        for (int index = 0; index < definitions.size(); index++) {
            String taskPath = path + "[" + index + "]";
            tasks.add(task(
                object(definitions.get(index), taskPath).deepCopy(),
                taskPath,
                idsByKey
            ));
        }
        return List.copyOf(tasks);
    }

    private Task task(
        ObjectNode definition,
        String path,
        Map<String, String> idsByKey
    ) {
        rejectTaskSystemFields(definition, path);
        String key = requiredText(definition, "key", path);
        String type = requiredExactText(definition, "type", path);
        Class<? extends Task> concreteType;
        try {
            concreteType = pluginRegistry.resolve(type, Task.class);
        } catch (RuntimeException exception) {
            throw materializationFailure(path, exception);
        }
        String id = idsByKey.computeIfAbsent(
            key,
            ignored -> StringUtil.newId()
        );

        definition.put("id", id);
        definition.put("key", key);
        normalizeDefinitionFields(
            definition,
            concreteType,
            path,
            idsByKey
        );

        try {
            Task task = jacksonMapper.jsonMapper().treeToValue(
                definition,
                Task.class
            );
            if (!task.identifiedBy(id)
                || !key.equals(task.key())
                || !type.equals(task.getType())) {
                throw new IllegalArgumentException(
                    path + " produced an inconsistent Task"
                );
            }
            return task;
        } catch (JsonProcessingException | RuntimeException exception) {
            throw materializationFailure(path, exception);
        }
    }

    /**
     * Recursively prepares Task and Data fields declared by the registered
     * concrete class. The plugin class remains the only definition shape;
     * this binder does not branch on Pause or any other concrete Task.
     */
    private void normalizeDefinitionFields(
        ObjectNode definition,
        Class<? extends Task> concreteType,
        String path,
        Map<String, String> idsByKey
    ) {
        for (Field field : fields(concreteType)) {
            String name = field.getName();
            JsonNode value = definition.get(name);
            if (value == null || value.isNull()) {
                continue;
            }
            Class<?> fieldType = field.getType();
            if (Task.class.isAssignableFrom(fieldType)) {
                Task nested = task(
                    object(value, path + "." + name).deepCopy(),
                    path + "." + name,
                    idsByKey
                );
                definition.set(name, jacksonMapper.toTree(nested));
                continue;
            }
            if (!List.class.isAssignableFrom(fieldType)) {
                continue;
            }

            Class<?> elementType = listElementType(field);
            if (elementType == null) {
                continue;
            }
            if (Task.class.isAssignableFrom(elementType)) {
                definition.set(
                    name,
                    jacksonMapper.toTree(tasks(
                        value,
                        path + "." + name,
                        idsByKey
                    ))
                );
            } else if (Input.class.isAssignableFrom(elementType)) {
                normalizeInputs(value, path + "." + name);
            } else if (Output.class.isAssignableFrom(elementType)) {
                normalizeOutputs(value, path + "." + name);
            }
        }
    }

    private static List<Field> fields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    private static Class<?> listElementType(Field field) {
        if (!(field.getGenericType() instanceof ParameterizedType listType)) {
            return null;
        }
        Type element = listType.getActualTypeArguments()[0];
        if (element instanceof Class<?> elementClass) {
            return elementClass;
        }
        if (element instanceof ParameterizedType parameterized
            && parameterized.getRawType() instanceof Class<?> rawClass) {
            return rawClass;
        }
        return null;
    }

    private List<Input<?>> inputs(JsonNode value, String path) {
        if (value == null) {
            return List.of();
        }
        normalizeInputs(value, path);
        ArrayNode definitions = (ArrayNode) value;
        List<Input<?>> inputs = new ArrayList<>(definitions.size());
        for (int index = 0; index < definitions.size(); index++) {
            String itemPath = path + "[" + index + "]";
            try {
                Input<?> input = jacksonMapper.jsonMapper().treeToValue(
                    definitions.get(index),
                    Input.class
                );
                input.validateDefinition();
                inputs.add(input);
            } catch (JsonProcessingException | RuntimeException exception) {
                throw materializationFailure(itemPath, exception);
            }
        }
        return List.copyOf(inputs);
    }

    private List<Output> outputs(JsonNode value, String path) {
        if (value == null) {
            return List.of();
        }
        normalizeOutputs(value, path);
        ArrayNode definitions = (ArrayNode) value;
        List<Output> outputs = new ArrayList<>(definitions.size());
        for (int index = 0; index < definitions.size(); index++) {
            String itemPath = path + "[" + index + "]";
            try {
                outputs.add(jacksonMapper.jsonMapper().treeToValue(
                    definitions.get(index),
                    Output.class
                ));
            } catch (JsonProcessingException | RuntimeException exception) {
                throw materializationFailure(itemPath, exception);
            }
        }
        return List.copyOf(outputs);
    }

    private void normalizeInputs(JsonNode value, String path) {
        if (value == null) {
            return;
        }
        if (!(value instanceof ArrayNode definitions)) {
            throw new IllegalArgumentException(path + " must be a list");
        }
        for (int index = 0; index < definitions.size(); index++) {
            String itemPath = path + "[" + index + "]";
            ObjectNode definition = object(
                definitions.get(index),
                itemPath
            );
            String key = requiredText(definition, "key", itemPath);
            DataType type = dataType(definition, itemPath);
            definition.put("key", key);
            definition.put("type", type.name());
            if (!definition.has("displayName")) {
                definition.put("displayName", key);
            }
            if (!definition.has("required")) {
                definition.put("required", false);
            }
            JsonNode defaultValue = definition.get("defaultValue");
            if (defaultValue != null && !defaultValue.isNull()) {
                try {
                    Object valueObject = jacksonMapper.convertValue(
                        defaultValue,
                        Object.class
                    );
                    definition.set(
                        "defaultValue",
                        jacksonMapper.toTree(type.normalize(valueObject))
                    );
                } catch (RuntimeException exception) {
                    throw materializationFailure(itemPath, exception);
                }
            }
        }
    }

    private void normalizeOutputs(JsonNode value, String path) {
        if (value == null) {
            return;
        }
        if (!(value instanceof ArrayNode definitions)) {
            throw new IllegalArgumentException(path + " must be a list");
        }
        for (int index = 0; index < definitions.size(); index++) {
            String itemPath = path + "[" + index + "]";
            ObjectNode definition = object(
                definitions.get(index),
                itemPath
            );
            definition.put(
                "key",
                requiredText(definition, "key", itemPath)
            );
            definition.put("type", dataType(definition, itemPath).name());
        }
    }

    private static DataType dataType(
        ObjectNode definition,
        String path
    ) {
        try {
            return DataType.parse(requiredText(
                definition,
                "type",
                path
            ));
        } catch (RuntimeException exception) {
            throw materializationFailure(path, exception);
        }
    }

    private static ObjectNode object(JsonNode value, String path) {
        if (!(value instanceof ObjectNode object)) {
            throw new IllegalArgumentException(path + " must be a map");
        }
        return object;
    }

    private static void rejectTaskSystemFields(
        ObjectNode definition,
        String path
    ) {
        for (String field : TASK_SYSTEM_FIELDS) {
            if (definition.has(field)) {
                throw new IllegalArgumentException(
                    path + " must not declare system field " + field
                );
            }
        }
    }

    private static void rejectUnknownFields(
        ObjectNode definition,
        Set<String> allowed,
        String path
    ) {
        Set<String> unknown = new LinkedHashSet<>();
        definition.fieldNames().forEachRemaining(unknown::add);
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                path + " contains unsupported fields: " + unknown
            );
        }
    }

    private static String requiredText(
        ObjectNode definition,
        String field,
        String path
    ) {
        JsonNode value = definition.get(field);
        if (value == null || !value.isTextual()
            || value.textValue().isBlank()) {
            throw new IllegalArgumentException(
                path + "." + field + " must be non-blank text"
            );
        }
        return value.textValue().trim();
    }

    private static String requiredExactText(
        ObjectNode definition,
        String field,
        String path
    ) {
        JsonNode value = definition.get(field);
        if (value == null || !value.isTextual()
            || value.textValue().isBlank()) {
            throw new IllegalArgumentException(
                path + "." + field + " must be non-blank text"
            );
        }
        return value.textValue();
    }

    private static String optionalText(
        ObjectNode definition,
        String field,
        String defaultValue,
        String path
    ) {
        JsonNode value = definition.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(
                path + "." + field + " must be text"
            );
        }
        return value.textValue().trim();
    }

    private static IllegalArgumentException materializationFailure(
        String path,
        Throwable exception
    ) {
        String detail = exception.getMessage() == null
            ? exception.getClass().getSimpleName()
            : exception.getMessage();
        return new IllegalArgumentException(
            path + " could not be materialized: " + detail,
            exception
        );
    }
}
