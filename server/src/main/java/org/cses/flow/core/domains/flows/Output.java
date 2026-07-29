package org.cses.flow.core.domains.flows;

import java.util.Objects;

/**
 * Immutable output definition owned by a Flow or Task.
 */
public final class Output implements Data {

    private final String key;
    private final String type;

    private Output(String key, String type) {
        this.key = requireText(key, "Output key");
        this.type = requireText(type, "Output type");
    }

    public static Output create(String key, String type) {
        return new Output(key, type);
    }

    public static Output rehydrate(String key, String type) {
        return new Output(key, type);
    }

    @Override
    public String getKey() {
        return key;
    }

    @Override
    public String getType() {
        return type;
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
            + ", type='" + type + '\''
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
