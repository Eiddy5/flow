package org.cses.flow.core.domains.flows;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.cses.flow.core.utils.RequiredUtil;

import java.util.Objects;

/**
 * Immutable output definition owned by a Flow or Task.
 */
public class Output implements Data {

    String key;
    DataType type;

    public Output() {
    }

    @JsonCreator
    private Output(
        @JsonProperty("key") String key,
        @JsonProperty("type") DataType type
    ) {
        this.key = requireText(key, "Output key");
        this.type = RequiredUtil.required(type, "Output type");
    }

    public static Output create(String key, DataType type) {
        return new Output(key, type);
    }

    public static Output rehydrate(String key, DataType type) {
        return new Output(key, type);
    }

    @Override
    public String getKey() {
        return key;
    }

    public DataType getType() {
        return type;
    }

    /**
     * 返回输出值类型，保留原 JSON type 字段。
     * @return 已声明的非空输出值类型
     */
    @Override
    @com.fasterxml.jackson.annotation.JsonIgnore
    public DataType getValueType() {
        return type;
    }

    public void valid(Object value) {
        normalized(value);
    }

    public Object normalized(Object value) {
        try {
            return type.normalize(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Output " + key + " must be " + type.name(),
                exception
            );
        }
    }

    @Override
    public boolean equals(Object value) {
        return this == value
            || value instanceof Output other
            && key.equals(other.key)
            && type.equals(other.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, type);
    }

    @Override
    public String toString() {
        return "Output{"
            + "key='" + key + '\''
            + ", type=" + type
            + '}';
    }

    private static String requireText(String value, String field) {
        return RequiredUtil.required(value, field + " must not be blank")
            .trim();
    }
}
