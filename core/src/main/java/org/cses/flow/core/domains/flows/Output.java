package org.cses.flow.core.domains.flows;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Immutable output definition owned by a Flow or Task.
 */
public final class Output implements Data {

    private final String key;
    private final DataType type;

    @JsonCreator
    private Output(
        @JsonProperty("key") String key,
        @JsonProperty("type") DataType type
    ) {
        this.key = requireText(key, "Output key");
        this.type = Objects.requireNonNull(type, "Output type");
    }

    public static Output create(String key, DataType type) {
        return new Output(key, type);
    }

    public static Output rehydrate(String key, DataType type) {
        return new Output(key, type);
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public DataType type() {
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
        if (this == value) {
            return true;
        }
        if (!(value instanceof Output other)) {
            return false;
        }
        return Objects.equals(key, other.key)
            && Objects.equals(type, other.type);
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
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                field + " must not be blank"
            );
        }
        return value.trim();
    }
}
