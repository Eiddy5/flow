package org.cses.flow.core.domains.flows.inputs;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

/**
 * Input definition for finite Float values.
 */
@JsonTypeName("FLOAT")
public class FloatInput extends Input<Float> {

    /**
     * 创建完整且合法的输入定义；Builder 与 JSON 创建入口共用此构造路径。
     * @param key 非空白字段标识
     * @param displayName 显示名称，null 时使用 key
     * @param required true 表示运行值必填
     * @param defaultValue 已转换的默认值，可以为 null
     * @throws IllegalArgumentException 当公共字段、默认值或具体类型约束非法时抛出
     */
    @Builder
    private FloatInput(
        String key,
        String displayName,
        boolean required,
        Float defaultValue
    ) {
        super(key, displayName, required, defaultValue);
        validateDefinition();
    }


    /**
     * 收齐 JSON/YAML 字段后创建合法定义，拒绝 key 和默认值的宽松类型转换。
     * @param key 原始字段标识，必须为非空白字符串
     * @param displayName 显示名称，null 时使用 key
     * @param required 可选必填标记，未提供或 null 时采用 false
     * @param defaultValue 原始默认值，可以为 null；非空时必须可安全转换
     * @return 已完成定义校验的 FloatInput
     * @throws IllegalArgumentException 当字段类型或完整定义非法时抛出
     */
    @JsonCreator
    public static FloatInput from(
        @JsonProperty("key") Object key,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("required") Boolean required,
        @JsonProperty("defaultValue") Object defaultValue
    ) {
        return new FloatInput(
            definitionKey(key), displayName, Boolean.TRUE.equals(required),
            defaultValue == null ? null : (Float) DataType.FLOAT.normalize(defaultValue)
        );
    }


    /**
     * 返回本类型的基础运行值类型。
     * @return 非空基础值类型
     */
    @Override
    public DataType getValueType() {
        return DataType.FLOAT;
    }

    /**
     * 将非空传输值转换为 Float，拒绝类型不兼容或越界的值。
     * @param value 非空传输值，不得修改
     * @return 转换后的 Float 值
     * @throws IllegalArgumentException 当值无法安全转换时抛出
     */
    @Override
    protected Float convert(Object value) {
        return (Float) DataType.FLOAT.normalize(value);
    }

    /**
     * 检查已转换的非空值是否符合本字段的约束。
     * @param value 已转换的非空值，不得修改
     * @throws IllegalArgumentException 当值违反范围或有限值约束时抛出
     */
    @Override
    protected void validateValue(Float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(
                "Value must be finite"
            );
        }
    }
}
