package org.cses.flow.infrastructure.repositories.flows.codec;

import org.cses.flow.core.domains.flows.Data;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.serializers.JacksonMapper;
import org.jooq.JSONB;
import org.paas.json.JsonObject;
import org.paas.json.JsonObjects;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;

public class DataJsonCodec {

    private DataJsonCodec() {
    }

    public static JsonObjects encode(List<? extends Data> values) {
        return JsonObjects.FromList(
            values.stream().map(DataJsonCodec::jsonValue).toList()
        );
    }

    public static JSONB encodeJsonb(List<? extends Data> values) {
        return JSONB.valueOf(encode(values).toJson());
    }

    /**
     * 恢复创建时已经检查完整定义的具体 Input，不在持久化层解释字段规则。
     * @param values 已解析的只读定义数组；null 表示没有输入声明
     * @param field 用于错误定位的所属字段路径
     * @return 恢复后的不可变 Input 列表
     * @throws IllegalStateException 当某项无法物化或定义校验失败时抛出，包含数组位置
     */
    public static List<Input<?>> decodeInputs(
        JsonObjects values,
        String field
    ) {
        if (values == null) {
            return List.of();
        }
        List<Input<?>> inputs = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            try {
                Input<?> input = JacksonMapper.convertPersistenceValue(
                    values.getObject(index).asMap(), Input.class
                );
                inputs.add(input);
            } catch (RuntimeException exception) {
                throw new IllegalStateException(
                    "Persisted " + field + "[" + index + "] is invalid",
                    exception
                );
            }
        }
        return List.copyOf(inputs);
    }

    public static List<Input<?>> decodeInputs(
        JSONB values,
        String field
    ) {
        return values == null
            ? List.of()
            : decodeInputs(JsonObjects.Parse(values.data()), field);
    }

    public static List<Output> decodeOutputs(
        JsonObjects values,
        String field
    ) {
        if (values == null) {
            return List.of();
        }
        return values.toListMap().stream()
            .map(value -> Output.rehydrate(
                requiredText(value, "key", field),
                DataType.parse(requiredText(value, "type", field))
            ))
            .toList();
    }

    public static List<Output> decodeOutputs(
        JSONB values,
        String field
    ) {
        return values == null
            ? List.of()
            : decodeOutputs(JsonObjects.Parse(values.data()), field);
    }

    private static Object jsonValue(Data data) {
        if (data instanceof Input<?> input) {
            JsonObject value = JsonObject.From(input);
            if (input.getDefaultValue() instanceof Character character) {
                value.replace("defaultValue", character.toString());
            }
            return value;
        }
        return Map.of(
            "key",
            data.getKey(),
            "type",
            data.getValueType().name()
        );
    }

    private static String requiredText(
        Map<String, Object> value,
        String property,
        String field
    ) {
        Object candidate = value.get(property);
        if (!(candidate instanceof String text) || text.isBlank()) {
            throw new IllegalStateException(
                "Persisted " + field + "." + property
                    + " must be non-blank text"
            );
        }
        return text.trim();
    }

}
