package org.cses.flow.core.plugins;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.cses.flow.core.domains.tasks.Task;
import org.paas.common.util.StringUtil;

import java.io.IOException;

import static java.util.Objects.requireNonNull;

/**
 * Resolves a plugin's exact class before Jackson binds its fields.
 */
public class PluginDeserializer<T extends Plugin>
    extends JsonDeserializer<T> {

    private PluginRegistry registry;
    private boolean sourceDefinition;

    public PluginDeserializer(PluginRegistry registry) {
        this(registry, false);
    }

    PluginDeserializer(
        PluginRegistry registry,
        boolean sourceDefinition
    ) {
        this.registry = requireNonNull(registry, "Plugin registry");
        this.sourceDefinition = sourceDefinition;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T deserialize(
        JsonParser parser,
        DeserializationContext context
    ) throws IOException {
        JsonNode value = context.readTree(parser);
        if (!(value instanceof ObjectNode object)) {
            throw JsonMappingException.from(
                parser,
                "Plugin must be an object"
            );
        }
        JsonNode typeNode = object.get("type");
        if (typeNode == null || !typeNode.isTextual()
            || typeNode.textValue().isBlank()) {
            throw context.weirdStringException(
                typeNode == null ? null : typeNode.asText(),
                Plugin.class,
                "Plugin type must be non-blank text"
            );
        }

        String type = typeNode.textValue();
        Class<? extends Plugin> concreteType;
        try {
            concreteType = registry.resolve(type, Plugin.class);
        } catch (IllegalArgumentException exception) {
            throw context.invalidTypeIdException(
                context.constructType(Plugin.class),
                type,
                exception.getMessage()
            );
        }

        ObjectNode fields = object.deepCopy();
        fields.remove("type");
        prepareSourceTask(fields, concreteType);
        return (T) context.readTreeAsValue(fields, concreteType);
    }

    private void prepareSourceTask(
        ObjectNode definition,
        Class<? extends Plugin> concreteType
    ) {
        if (!sourceDefinition
            || !Task.class.isAssignableFrom(concreteType)) {
            return;
        }

        rejectSystemField(definition, "id");
        rejectSystemField(definition, "parentId");
        rejectSystemField(definition, "taskId");

        JsonNode keyNode = definition.get("key");
        String key;
        if (keyNode == null || keyNode.isNull()) {
            key = StringUtil.newId();
        } else if (!keyNode.isTextual() || keyNode.textValue().isBlank()) {
            throw new IllegalArgumentException(
                "Task key must be non-blank text"
            );
        } else {
            key = keyNode.textValue().trim();
        }

        definition.put("key", key);
        definition.put("id", StringUtil.newId());
    }

    private static void rejectSystemField(
        ObjectNode definition,
        String field
    ) {
        if (definition.has(field)) {
            throw new IllegalArgumentException(
                "Task must not declare system field " + field
            );
        }
    }
}
