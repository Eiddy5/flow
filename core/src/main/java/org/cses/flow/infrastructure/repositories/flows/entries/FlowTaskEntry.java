package org.cses.flow.infrastructure.repositories.flows.entries;

import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.serializers.JacksonMapper;
import org.flow.gen.flow.pojos.FlowTasksObject;
import org.paas.json.JsonObject;
import org.paas.json.JsonObjects;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FlowTaskEntry extends FlowTasksObject {

    private static final Set<String> CORE_FIELDS = Set.of(
        "id",
        "type",
        "key",
        "inputs",
        "outputs",
        "route",
        "dependOn",
        "tasks"
    );

    public static FlowTaskEntry fromDomain(
        String companyId,
        String flowKey,
        long flowVersion,
        Task task,
        String parentId,
        int order,
        JacksonMapper jacksonMapper
    ) {
        FlowTaskEntry entry = new FlowTaskEntry();
        entry.companyId = companyId;
        entry.id = task.id();
        entry.type = task.getType();
        entry.route = task.route().source();
        entry.inputs = DataJsonCodec.encode(task.inputs());
        entry.outputs = DataJsonCodec.encode(task.outputs());
        entry.properties = JsonObject.FromMap(properties(task, jacksonMapper));
        entry.flowKey = flowKey;
        entry.flowVersion = flowVersion;
        entry.parentId = parentId;
        entry.order = order;
        entry.key = task.key();
        entry.dependOn = JsonObjects.FromList(task.dependOn());
        return entry;
    }

    public Task toDomain(
        JacksonMapper jacksonMapper,
        List<Task> children
    ) {
        Map<String, Object> definition = new LinkedHashMap<>();
        if (properties != null) {
            definition.putAll(properties.asMap());
        }
        definition.put("id", id);
        definition.put("type", type);
        definition.put("key", key);
        definition.put(
            "inputs",
            DataJsonCodec.decodeInputs(inputs, "Task.inputs")
        );
        definition.put(
            "outputs",
            DataJsonCodec.decodeOutputs(outputs, "Task.outputs")
        );
        definition.put("route", route);
        definition.put(
            "dependOn",
            dependOn == null ? List.of() : dependOn.asStrings()
        );
        definition.put("tasks", List.copyOf(children));
        return jacksonMapper.convertValue(definition, Task.class);
    }

    private static Map<String, Object> properties(
        Task task,
        JacksonMapper jacksonMapper
    ) {
        Map<String, Object> serialized = new LinkedHashMap<>(
            jacksonMapper.toMap(task)
        );
        CORE_FIELDS.forEach(serialized::remove);
        return Map.copyOf(serialized);
    }
}
