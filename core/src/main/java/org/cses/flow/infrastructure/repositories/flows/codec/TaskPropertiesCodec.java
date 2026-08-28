package org.cses.flow.infrastructure.repositories.flows.codec;

import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.serializers.JacksonMapper;
import org.jooq.JSONB;
import org.paas.json.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TaskPropertiesCodec {

    private static Set<String> CORE_FIELDS = Set.of(
        "id",
        "type",
        "key",
        "displayName",
        "inputs",
        "outputs",
        "dependOn",
        "tasks"
    );

    private TaskPropertiesCodec() {
    }

    public static JsonObject encode(Task task) {
        Map<String, Object> serialized = new LinkedHashMap<>(
            JacksonMapper.toPersistenceMap(task)
        );
        CORE_FIELDS.forEach(serialized::remove);
        return JsonObject.FromMap(Map.copyOf(serialized));
    }

    public static JSONB encodeJsonb(Task task) {
        return JSONB.valueOf(encode(task).toJson());
    }

    public static Task decode(
        JsonObject value,
        String id,
        String type,
        String key,
        List<Input<?>> inputs,
        List<Output> outputs,
        List<Task> children
    ) {
        Map<String, Object> definition = new LinkedHashMap<>();
        if (value != null) {
            definition.putAll(value.asMap());
        }
        definition.put("id", id);
        definition.put("type", type);
        definition.put("key", key);
        definition.put("inputs", List.copyOf(inputs));
        definition.put("outputs", List.copyOf(outputs));
        if (children != null && !children.isEmpty()) {
            definition.put("tasks", List.copyOf(children));
        }
        return JacksonMapper.convertPersistenceValue(
            definition,
            Task.class
        );
    }

    public static Task decode(
        JSONB value,
        String id,
        String type,
        String key,
        List<Input<?>> inputs,
        List<Output> outputs,
        List<Task> children
    ) {
        return decode(
            value == null ? null : JsonObject.Parse(value.data()),
            id,
            type,
            key,
            inputs,
            outputs,
            children
        );
    }
}
