package org.cses.flow.core.serializers;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stateless utility for parsing YAML into caller-requested Java objects
 * without applying business interpretation.
 */
public class YamlParser {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE =
        new TypeReference<>() {
        };

    private YamlParser() {
    }

    /**
     * Parses a YAML mapping into a deeply read-only generic object graph.
     */
    public static Map<String, Object> parse(String source) {
        ObjectMapper objectMapper = JacksonMapper.yamlMapper();
        return immutableStringMap(
            read(source, objectMapper.readerFor(MAP_TYPE_REFERENCE)),
            "YAML root"
        );
    }

    public static <T> T parse(String source, Class<T> type) {
        Objects.requireNonNull(type, "target type");
        ObjectMapper objectMapper = JacksonMapper.yamlMapper();
        return read(source, objectMapper.readerFor(type));
    }

    private static <T> T read(String source, ObjectReader reader) {
        requireSource(source);
        try {
            return reader.readValue(source);
        } catch (JsonProcessingException exception) {
            throw parseFailure(exception);
        }
    }

    private static void requireSource(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException(
                "YAML source must not be blank"
            );
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
