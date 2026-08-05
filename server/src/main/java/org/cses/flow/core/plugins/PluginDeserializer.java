package org.cses.flow.core.plugins;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.cses.flow.core.validations.ModelValidator;

import java.io.IOException;

/**
 * Resolves a plugin's exact class before Jackson binds its fields.
 */
public final class PluginDeserializer<T extends Plugin>
    extends JsonDeserializer<T> {

    private final PluginRegistry registry;
    private final Class<T> pluginType;
    private final ModelValidator modelValidator;

    public PluginDeserializer(
        PluginRegistry registry,
        Class<T> pluginType,
        ModelValidator modelValidator
    ) {
        this.registry = registry;
        this.pluginType = pluginType;
        this.modelValidator = modelValidator;
    }

    @Override
    public T deserialize(
        JsonParser parser,
        DeserializationContext context
    ) throws IOException {
        JsonNode value = context.readTree(parser);
        if (!(value instanceof ObjectNode object)) {
            throw JsonMappingException.from(
                parser,
                pluginType.getSimpleName() + " must be an object"
            );
        }
        JsonNode typeNode = object.get("type");
        if (typeNode == null || !typeNode.isTextual()
            || typeNode.textValue().isBlank()) {
            throw context.weirdStringException(
                typeNode == null ? null : typeNode.asText(),
                pluginType,
                "Plugin type must be non-blank text"
            );
        }

        String type = typeNode.textValue();
        Class<? extends T> concreteType;
        try {
            concreteType = registry.resolve(type, pluginType);
        } catch (IllegalArgumentException exception) {
            throw context.invalidTypeIdException(
                context.constructType(pluginType),
                type,
                exception.getMessage()
            );
        }

        ObjectNode fields = object.deepCopy();
        fields.remove("type");
        T plugin = parser.getCodec().treeToValue(fields, concreteType);
        return modelValidator.validate(plugin);
    }
}
