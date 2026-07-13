package org.cses.flow.definition.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ImmutableValue {

    private ImmutableValue() {
    }

    static Map<String, Object> copyMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, copy(value)));
        return Map.copyOf(copy);
    }

    private static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(String.valueOf(key), copy(nested)));
            return Map.copyOf(copy);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ImmutableValue::copy).toList();
        }
        return value;
    }
}
