package org.cses.flow.core.domains.conditions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable variable snapshot for one Condition evaluation.
 */
public class ConditionContext {

    private Map<String, Object> variables;

    private ConditionContext(Map<String, ?> variables) {
        this.variables = immutableMap(variables);
    }

    public static ConditionContext from(Map<String, ?> variables) {
        return new ConditionContext(variables);
    }

    Optional<Object> resolve(Operand operand) {
        if (operand == null || !operand.isReference()) {
            return Optional.empty();
        }
        return operand.resolve(variables);
    }

    private static Map<String, Object> immutableMap(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> copy = new LinkedHashMap<>();
            nested.forEach((key, nestedValue) -> copy.put(
                String.valueOf(key),
                immutableValue(nestedValue)
            ));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream()
                .map(ConditionContext::immutableValue)
                .toList();
        }
        return value;
    }
}
