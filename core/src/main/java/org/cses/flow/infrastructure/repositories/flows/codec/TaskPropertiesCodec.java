package org.cses.flow.infrastructure.repositories.flows.codec;

import org.cses.flow.core.domains.flows.Input;
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

    /**
     * 从插件属性与身份恢复任务，输出字段由具体 Output 类型重新推导。
     * @param value 插件属性，可为 null
     * @param id 原任务身份，非 null
     * @param type 插件类型，非 null
     * @param key 任务业务标识，非 null
     * @param inputs 非 null 的输入定义，只读
     * @param children 普通子任务，只读；null 表示无子任务
     * @return 恢复的具体 Task，保留原身份
     */
    public static Task decode(
        JsonObject value,
        String id,
        String type,
        String key,
        List<Input<?>> inputs,
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
        if (children != null && !children.isEmpty()) {
            definition.put("tasks", List.copyOf(children));
        }
        return JacksonMapper.convertPersistenceValue(
            definition,
            Task.class
        );
    }

    /**
     * 解码 JSONB 后按同一字段协议恢复任务。
     * @param value 插件属性 JSONB，可为 null
     * @param id 原任务身份，非 null
     * @param type 插件类型，非 null
     * @param key 任务业务标识，非 null
     * @param inputs 非 null 的输入定义，只读
     * @param children 普通子任务，只读；null 表示无子任务
     * @return 恢复的具体 Task，保留原身份
     */
    public static Task decode(
        JSONB value,
        String id,
        String type,
        String key,
        List<Input<?>> inputs,
        List<Task> children
    ) {
        return decode(
            value == null ? null : JsonObject.Parse(value.data()),
            id,
            type,
            key,
            inputs,
            children
        );
    }
}
