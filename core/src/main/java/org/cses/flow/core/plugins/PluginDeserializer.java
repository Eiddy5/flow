package org.cses.flow.core.plugins;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.cses.flow.core.domains.tasks.Task;

import java.io.IOException;

import static java.util.Objects.requireNonNull;

/**
 * Resolves a plugin's exact class before Jackson binds its fields.
 */
public final class PluginDeserializer<T extends Plugin>
    extends JsonDeserializer<T> {

    private final PluginRegistry registry;

    public PluginDeserializer(PluginRegistry registry) {
        this.registry = requireNonNull(registry, "Plugin registry");
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
        prepareTask(fields, context, concreteType);
        return (T) context.readTreeAsValue(fields, concreteType);
    }

    private static void prepareTask(
        ObjectNode definition,
        DeserializationContext context,
        Class<? extends Plugin> concreteType
    ) {
        if (!Task.class.isAssignableFrom(concreteType)) {
            return;
        }
        Object attribute = context.getAttribute(
            PluginDeserializationContext.ATTRIBUTE
        );
        if (attribute instanceof PluginDeserializationContext binding) {
            binding.prepareTask(definition);
        }
    }
}
