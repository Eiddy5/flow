package org.cses.flow.core.domains.flows;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Getter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.cses.flow.core.plugins.Plugin;
import org.cses.flow.core.plugins.InputTypes;
import org.cses.flow.core.utils.RequiredUtil;
import org.paas.json.SerializableObject;

import java.util.Objects;
import java.util.Map;

/**
 * Base description of one Flow or Task input.
 *
 * @param <T> accepted Java wrapper value type
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type"
)
public abstract class Input<T> extends SerializableObject implements Data, Plugin {

    /**
     * 返回具体 Input 自身声明的 Jackson 类型名称。
     * @return 与序列化和插件目录一致的类型名称
     * @throws IllegalStateException 当具体类缺少合法 JsonTypeName 时抛出
     */
    @Override
    public final String getType() {
        return InputTypes.name(getClass());
    }

    /**
     * 返回本 Input 接受的基础值类型，供条件兼容性检查使用，不作为定义类型标识序列化。
     * @return 非空基础值类型；业务类型可复用同一种值类型
     */
    @Override
    @JsonIgnore
    public abstract DataType getValueType();

    private String key;
    private String displayName;
    private boolean required;
    private T defaultValue;

    /**
     * 保存创建具体 Input 所需的公共字段；子类字段就位后由子类完成定义校验。
     * @param key 字段标识，最终必须为非空白文本
     * @param displayName 显示名称；null 时由定义校验采用 key
     * @param required true 表示运行提交必须得到非空值
     * @param defaultValue 已转换的默认值，可以为 null
     */
    protected Input(String key, String displayName, boolean required, T defaultValue) {
        this.key = key;
        this.displayName = displayName;
        this.required = required;
        this.defaultValue = defaultValue;
    }

    @JsonAnySetter
    private void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException(
            "Unsupported Input field: " + field
        );
    }

    /**
     * 校验并规范化本字段的定义及默认值；完成后才能进入所属定义。
     * @throws IllegalArgumentException 当名称、子类约束或默认值非法时抛出
     */
    protected final void validateDefinition() {
        key = requireText(key, "Input key");
        displayName = displayName == null
            ? key
            : requireText(displayName, "Input displayName");
        validateSubtypeDefinition();
        if (defaultValue != null) {
            defaultValue = acceptedValue(defaultValue);
        }
    }

    /**
     * 从提交映射中绑定本字段；仅缺失字段使用默认值，显式 null 不触发默认值。
     * @param submittedInputs 只读提交映射，不能为 null；其他字段由所属定义处理
     * @return 已转换且通过本字段规则的值；非必填字段允许返回 null
     * @throws IllegalArgumentException 当必填字段为空或值不符合本字段规则时抛出
     * @throws NullPointerException 当提交映射为 null 时抛出
     */
    public final T bind(Map<String, ?> submittedInputs) {
        Objects.requireNonNull(submittedInputs, "Submitted inputs");
        return acceptedValue(submittedInputs.containsKey(key)
            ? submittedInputs.get(key)
            : defaultValue);
    }

    /**
     * 将非空传输值转换为当前具体 Input 接受的值类型。
     * @param value 非空传输值，不得修改
     * @return 转换后的非空值
     * @throws IllegalArgumentException 当值无法安全转换时抛出
     */
    protected abstract T convert(Object value);

    /**
     * 校验转换后的非空值；基础 Input 没有额外规则。
     * @param value 已转换的非空值，不得修改
     * @throws IllegalArgumentException 当值违反具体 Input 的约束时抛出
     */
    protected void validateValue(T value) {
    }

    /**
     * 接受定义中的原始字段标识，拒绝框架将其他值类型宽松转换为字符串。
     * @param value 原始 key，必须为非空白字符串
     * @return 去除首尾空白后的字段标识
     * @throws IllegalArgumentException 当 key 缺失、类型不正确或为空白时抛出
     */
    protected static String definitionKey(Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Input key must be non-blank text");
        }
        return requireText(text, "Input key");
    }

    /**
     * 完成本字段的必填、转换和具体值规则检查，并为失败添加字段标识。
     * @param value 待接受的提交值或默认值，可以为 null
     * @return 合法值；仅非必填字段允许返回 null
     * @throws IllegalArgumentException 当值为空但必填，或具体转换与约束检查失败时抛出
     */
    private T acceptedValue(Object value) {
        if (value == null) {
            if (required) {
                throw new IllegalArgumentException("Input " + key + " is required");
            }
            return null;
        }
        try {
            T accepted = Objects.requireNonNull(convert(value), "Converted input");
            validateValue(accepted);
            return accepted;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Input " + key + ": " + exception.getMessage(), exception
            );
        }
    }

    protected void validateSubtypeDefinition() {
    }

    protected boolean specificEquals(Input<?> other) {
        return true;
    }

    protected int specificHashCode() {
        return 0;
    }

    @Override
    public final boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof Input<?> other)) {
            return false;
        }
        return getClass().equals(other.getClass())
            && required == other.required
            && Objects.equals(key, other.key)
            && Objects.equals(displayName, other.displayName)
            && Objects.equals(defaultValue, other.defaultValue)
            && specificEquals(other);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(
            getClass(),
            key,
            displayName,
            required,
            defaultValue,
            specificHashCode()
        );
    }

    private static String requireText(String value, String field) {
        return RequiredUtil.required(value, field + " must not be blank")
            .trim();
    }
}
