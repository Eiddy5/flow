package org.cses.flow.core.serializers;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.tasks.Task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Projects one immutable deployed Flow revision back to its public definition.
 * Plugin-specific fields are serialized by Flow's registered Jackson module;
 * callers do not need to understand or duplicate plugin contracts.
 */
@Singleton
public final class FlowDefinitionSerializer {

    private final JacksonMapper jacksonMapper;

    @Inject
    public FlowDefinitionSerializer(JacksonMapper jacksonMapper) {
        this.jacksonMapper = Objects.requireNonNull(
                jacksonMapper,
                "jacksonMapper"
        );
    }

    public String serialize(Flow flow) {
        return jacksonMapper.writeYaml(definition(flow));
    }

    public Map<String, Object> definition(Flow flow) {
        Objects.requireNonNull(flow, "flow");
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("key", flow.key());
        definition.put("description", flow.description());
        definition.put("variables", definitionValue(flow.variables()));
        definition.put(
                "inputs",
                flow.inputs().stream()
                        .map(jacksonMapper::toMap)
                        .map(this::definitionValue)
                        .toList()
        );
        definition.put(
                "outputs",
                flow.outputs().stream()
                        .map(jacksonMapper::toMap)
                        .map(this::definitionValue)
                        .toList()
        );
        definition.put(
                "tasks",
                flow.tasks().stream().map(this::taskDefinition).toList()
        );
        return Map.copyOf(definition);
    }

    private Map<String, Object> taskDefinition(Task task) {
        Map<String, Object> definition = new LinkedHashMap<>(
                jacksonMapper.toMap(task)
        );
        definition.remove("id");
        return definitionMap(definition, true);
    }

    private Object definitionValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return definitionMap(map, false);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(definitionValue(item)));
            return List.copyOf(result);
        }
        return value;
    }

    private Map<String, Object> definitionMap(
            Map<?, ?> source,
            boolean knownTask
    ) {
        boolean task = knownTask || isTaskDefinition(source);
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (task && name.equals("id")) return;
            result.put(name, definitionValue(value));
        });
        return Map.copyOf(result);
    }

    private static boolean isTaskDefinition(Map<?, ?> source) {
        return source.containsKey("key")
                && source.containsKey("type");
    }
}
