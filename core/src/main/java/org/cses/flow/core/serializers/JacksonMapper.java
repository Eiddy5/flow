package org.cses.flow.core.serializers;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.inject.Singleton;
import org.cses.flow.core.plugins.PluginModule;

import java.io.IOException;
import java.util.Map;

/**
 * Central strict Jackson configuration for Flow definition serialization.
 */
@Singleton
public final class JacksonMapper {

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
        new TypeReference<>() {
        };

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;

    public JacksonMapper(PluginModule pluginModule) {
        this.jsonMapper = configure(new ObjectMapper(), pluginModule);
        YAMLFactory yamlFactory = new YAMLFactory();
        yamlFactory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.yamlMapper = configure(
            new ObjectMapper(yamlFactory),
            pluginModule
        );
    }

    public <T> T convertValue(Object value, Class<T> type) {
        return jsonMapper.convertValue(value, type);
    }

    public Map<String, Object> toMap(Object value) {
        return jsonMapper.convertValue(value, MAP_TYPE);
    }

    public <T> T readTree(JsonNode value, Class<T> type) {
        try {
            return jsonMapper.readerFor(type).readValue(value);
        } catch (IOException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
    }

    public <T> T readTree(
        JsonNode value,
        Class<T> type,
        Map<?, ?> attributes
    ) {
        try {
            ObjectReader reader = jsonMapper.readerFor(type)
                .withAttributes(attributes);
            return reader.readValue(value);
        } catch (IOException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
    }

    public String writeYaml(Object value) {
        try {
            return yamlMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "Value could not be serialized as YAML",
                exception
            );
        }
    }

    ObjectNode toObjectNode(Object value) {
        return jsonMapper.valueToTree(value);
    }

    JsonNode toTree(Object value) {
        return jsonMapper.valueToTree(value);
    }

    ObjectMapper jsonMapper() {
        return jsonMapper;
    }

    ObjectMapper yamlMapper() {
        return yamlMapper;
    }

    private static ObjectMapper configure(
        ObjectMapper mapper,
        PluginModule pluginModule
    ) {
        return mapper
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
            .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
            .setVisibility(
                PropertyAccessor.FIELD,
                JsonAutoDetect.Visibility.ANY
            )
            .setVisibility(
                PropertyAccessor.GETTER,
                JsonAutoDetect.Visibility.PUBLIC_ONLY
            )
            .setVisibility(
                PropertyAccessor.IS_GETTER,
                JsonAutoDetect.Visibility.PUBLIC_ONLY
            )
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .registerModule(pluginModule);
    }
}
