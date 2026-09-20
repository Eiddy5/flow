package org.cses.flow.infrastructure.repositories.executions.codec;

import io.micronaut.json.tree.JsonNode;
import org.paas.json.JsonFactory;
import org.paas.json.JsonObject;
import org.paas.json.JsonObjects;

import java.util.Map;

/** 编码任务值及需要完整保留的调用变量快照。 */
public class TaskRunValuesJsonCodec {

    /** 禁止实例化。 */
    private TaskRunValuesJsonCodec() {}

    /**
     * 沿用任务输入输出的既有序列化协议。
     * @param values 任务值映射，只读
     * @return 任务值 JSON 对象
     */
    public static JsonObject encode(Map<String, Object> values) {
        return JsonObject.FromMap(values);
    }

    /**
     * 按树节点编码，避免通用对象序列化的 NON_EMPTY 配置丢弃变量路径。
     * @param values 非 null 的变量映射，只读
     * @return 完整保留空集合和 null 的 JSON 对象
     */
    public static JsonObject encodeVariables(Map<String, Object> values) {
        return (JsonObject) node(java.util.Objects.requireNonNull(values, "TaskRun values"));
    }

    /**
     * 递归构建 PAAS 树，标量复用现有编码器。
     * @param value 可空的 JSON 兼容值
     * @return 包含空值或空容器的节点
     */
    private static JsonNode node(Object value) {
        if (value instanceof Map<?, ?> map) {
            JsonObject object = JsonObject.Create();
            map.forEach((key, child) -> object.put((String) key, node(child)));
            return object;
        }
        if (value instanceof java.util.List<?> list) {
            JsonObjects array = JsonObjects.Create();
            list.forEach(child -> array._add(node(child)));
            return array;
        }
        return JsonFactory.From(value);
    }

    /**
     * 恢复变量树，空列按已有调用协议返回空映射。
     * @param values 可空 JSON 对象
     * @return 独立映射
     */
    public static Map<String, Object> decode(JsonObject values) {
        return values == null ? Map.of() : values.asMap();
    }
}
