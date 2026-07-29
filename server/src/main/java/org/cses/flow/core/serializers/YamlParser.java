package org.cses.flow.core.serializers;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a YAML mapping without applying any business interpretation.
 */
@Singleton
public final class YamlParser {

    private final ObjectMapper objectMapper;

    public YamlParser() {
        YAMLFactory yamlFactory = new YAMLFactory();
        yamlFactory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        objectMapper = new ObjectMapper(yamlFactory);
        objectMapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public Map<String, Object> parse(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException(
                "YAML source must not be blank"
            );
        }
        try {
            Object parsed = objectMapper.readValue(source, Object.class);
            return immutableStringMap(parsed, "YAML root");
        } catch (JsonProcessingException exception) {
            throw parseFailure(exception);
        }
    }

    private static IllegalArgumentException parseFailure(
        JsonProcessingException exception
    ) {
        StringBuilder message = new StringBuilder("Failed to parse YAML");
        JsonLocation location = exception.getLocation();
        if (location != null && location.getLineNr() > 0) {
            message.append(" at line ").append(location.getLineNr());
            if (location.getColumnNr() > 0) {
                message.append(", column ").append(location.getColumnNr());
            }
        }
        String detail = exception.getOriginalMessage();
        if (detail != null && !detail.isBlank()) {
            message.append(": ").append(detail);
        }
        return new IllegalArgumentException(message.toString(), exception);
    }

    private static Map<String, Object> immutableStringMap(
        Object value,
        String path
    ) {
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException(path + " must be a map");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, nestedValue) -> {
            if (!(key instanceof String textKey)) {
                throw new IllegalArgumentException(
                    path + " contains a non-text key: " + key
                );
            }
            result.put(
                textKey,
                immutableValue(nestedValue, path + "." + textKey)
            );
        });
        return Collections.unmodifiableMap(result);
    }

    private static Object immutableValue(Object value, String path) {
        if (value instanceof Map<?, ?>) {
            return immutableStringMap(value, path);
        }
        if (value instanceof List<?> source) {
            List<Object> result = new ArrayList<>(source.size());
            for (int index = 0; index < source.size(); index++) {
                result.add(immutableValue(
                    source.get(index),
                    path + "[" + index + "]"
                ));
            }
            return List.copyOf(result);
        }
        return value;
    }
}
