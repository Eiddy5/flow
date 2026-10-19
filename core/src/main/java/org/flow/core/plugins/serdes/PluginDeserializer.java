package org.flow.core.plugins.serdes;

import java.io.IOException;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

import org.flow.core.contexts.FlowContext;
import org.flow.plugin.api.Plugin;
import org.flow.core.plugins.DefaultPluginRegistry;
import org.flow.core.plugins.PluginRegistry;

import io.micronaut.context.exceptions.NoSuchBeanException;
import lombok.extern.slf4j.Slf4j;

/**
 * Specific {@link JsonDeserializer} for deserializing classes that implements the {@link Plugin} interface.
 * <p>
 * The {@link PluginDeserializer} uses the {@link PluginRegistry} to found the plugin class corresponding to
 * a plugin type.
 */
@Slf4j
public class PluginDeserializer<T extends Plugin> extends JsonDeserializer<T> {

    private static final String TYPE = "type";
    private static final String VERSION = "version";

    private volatile PluginRegistry pluginRegistry;

    /**
     * Creates a new {@link PluginDeserializer} instance.
     */
    public PluginDeserializer() {
    }

    /**
     * Creates a new {@link PluginDeserializer} instance.
     *
     * @param pluginRegistry The {@link PluginRegistry}.
     */
    PluginDeserializer(final PluginRegistry pluginRegistry) {
        this.pluginRegistry = pluginRegistry;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        checkState();
        JsonNode node = parser.readValueAsTree();
        if (node.isObject()) {
            return fromObjectNode(parser, node, context);
        } else {
            return null;
        }
    }

    private void checkState() {
        if (pluginRegistry == null) {
            try {
                // By default, if no plugin-registry is configured retrieve
                // the one configured from the static Flow's context.
                pluginRegistry = FlowContext.getContext().getPluginRegistry();
            } catch (IllegalStateException | NoSuchBeanException ignore) {
                // This error can only happen if the FlowContext is not initialized (i.e. in unit tests).
                log.error("No plugin registry was initialized. Use default implementation.");
                pluginRegistry = DefaultPluginRegistry.getOrCreate();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private T fromObjectNode(JsonParser jp,
        JsonNode node,
        DeserializationContext context) throws IOException {
        Class<? extends Plugin> pluginType;

        final String identifier = extractPluginRawIdentifier(node, pluginRegistry.isVersioningSupported());
        if (identifier == null) {
            pluginType = defaultClass();
        } else {
            log.trace(
                "Looking for Plugin for: {}",
                identifier
            );
            pluginType = pluginRegistry.findClassByIdentifier(identifier);

            if (pluginType == null) {
                pluginType = fallbackClass();
            }
        }

        if (pluginType == null) {
            String type = Optional.ofNullable(identifier).orElse("<null>");
            throwInvalidTypeException(context, type);
        } else if (Plugin.class.isAssignableFrom(pluginType)) {
            log.trace(
                "Read plugin for: {}",
                pluginType.getName()
            );


            // Note that if the provided plugin is not annotated with `@JsonDeserialize()` then
            // the following method will end up to a StackOverflowException as the `PluginDeserializer` will be re-invoked.
            return (T) jp.getCodec().treeToValue(node, pluginType);
        }

        // should not happen.
        log.warn("Failed get plugin type from JsonNode");
        return null;
    }

    private static void throwInvalidTypeException(final DeserializationContext context,
        final String type) throws JsonMappingException {
        throw context.invalidTypeIdException(
            context.constructType(Plugin.class),
            type,
            "No plugin registered for the defined type: '" + type + "'"
        );
    }

    static String extractPluginRawIdentifier(final JsonNode node, final boolean isVersioningSupported) {
        String type = Optional.ofNullable(node.get(TYPE)).map(JsonNode::textValue).orElse(null);
        String version = Optional.ofNullable(node.get(VERSION)).map(JsonNode::asText).orElse(null);

        if (type == null || type.isEmpty()) {
            return null;
        }

        return isVersioningSupported && version != null && !version.isEmpty() ? type + ":" + version : type;
    }

    protected Class<? extends Plugin> fallbackClass() {
        return null;
    }

    /**
     * The class to deserialize to when no type is declared, {@code null} making a missing type an error.
     */
    protected Class<? extends Plugin> defaultClass() {
        return null;
    }
}
