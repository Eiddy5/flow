package org.cses.flow.core.serializers;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import jakarta.inject.Singleton;
import org.cses.flow.core.plugins.PluginModule;

import java.util.Map;

/**
 * Central strict Jackson configuration for JSON and YAML serialization.
 */
@Singleton
public class JacksonMapper {

    private static TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE =
        new TypeReference<>() {
    };
    private static ObjectMapper BASE_YAML_MAPPER = JacksonMapper.configure(
        new ObjectMapper(
            YAMLFactory
                .builder()
                .configure(YAMLGenerator.Feature.MINIMIZE_QUOTES, true)
                .configure(
                    YAMLGenerator.Feature.WRITE_DOC_START_MARKER,
                    false
                )
                .configure(YAMLGenerator.Feature.USE_NATIVE_TYPE_ID, false)
                .configure(YAMLGenerator.Feature.SPLIT_LINES, false)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build()
        )
    );
    /**
     * The source-definition mapper used by the static YamlParser entry point.
     */
    private static ObjectMapper YAML_MAPPER = BASE_YAML_MAPPER;

    private ObjectMapper jsonMapper;

    public JacksonMapper(PluginModule pluginModule) {
        jsonMapper = configure(new ObjectMapper(), pluginModule);
        YAML_MAPPER = BASE_YAML_MAPPER.copy()
            .registerModule(pluginModule.sourceDefinitions());
    }

    public <T> T convertValue(Object value, Class<T> type) {
        return jsonMapper.convertValue(value, type);
    }

    public Map<String, Object> toMap(Object value) {
        return jsonMapper.convertValue(value, MAP_TYPE_REFERENCE);
    }

    public String writeYaml(Object value) {
        try {
            return YAML_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "Value could not be serialized as YAML",
                exception
            );
        }
    }

    ObjectMapper jsonMapper() {
        return jsonMapper;
    }

    private static ObjectMapper configure(
        ObjectMapper mapper,
        PluginModule pluginModule
    ) {
        return configure(mapper).registerModule(pluginModule);
    }

    private static ObjectMapper configure(ObjectMapper mapper) {
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
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    static ObjectMapper yamlMapper() {
        return YAML_MAPPER;
    }
}
