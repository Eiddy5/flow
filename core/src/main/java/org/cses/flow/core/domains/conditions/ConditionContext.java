package org.cses.flow.core.domains.conditions;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ConditionContext {

    private final Map<String, Object> variables;
    private final Map<String, Object> inputs;
    private final Map<String, Object> outputs;

    private ConditionContext(
        Map<String, ?> variables,
        Map<String, ?> inputs,
        Map<String, ?> outputs
    ) {
        this.variables = immutableMap(variables);
        this.inputs = immutableMap(inputs);
        this.outputs = immutableMap(outputs);
    }

    public static ConditionContext create(
        Map<String, ?> variables,
        Map<String, ?> inputs,
        Map<String, ?> outputs
    ) {
        return new ConditionContext(variables, inputs, outputs);
    }

    Optional<Object> resolve(Operand operand) {
        if (operand == null || !operand.isReference()) {
            return Optional.empty();
        }
        Object current = switch (operand.scope()) {
            case VARIABLES -> variables;
            case INPUTS -> inputs;
            case OUTPUTS -> outputs;
        };
        List<String> path = operand.path();
        for (String segment : path) {
            if (!(current instanceof Map<?, ?> values)
                || !values.containsKey(segment)) {
                return Optional.empty();
            }
            current = values.get(segment);
            if (current == null) {
                return Optional.empty();
            }
        }
        return Optional.of(current);
    }

    private static Map<String, Object> immutableMap(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Object> copy =
            new java.util.LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            java.util.LinkedHashMap<String, Object> copy =
                new java.util.LinkedHashMap<>();
            nested.forEach((key, nestedValue) -> copy.put(
                String.valueOf(key),
                immutableValue(nestedValue)
            ));
            return Collections.unmodifiableMap(copy);
        }
        return value;
    }
}
