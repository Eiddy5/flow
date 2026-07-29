package org.cses.flow.core.domains.flows;

import java.util.Objects;

/**
 * Immutable input definition owned by a Flow or Task.
 */
public final class Input implements Data {

    private final String key;
    private final String type;

    private Input(String key, String type) {
        this.key = requireText(key, "Input key");
        this.type = requireText(type, "Input type");
    }

    public static Input create(String key, String type) {
        return new Input(key, type);
    }

    public static Input rehydrate(String key, String type) {
        return new Input(key, type);
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
        if (!(value instanceof Input other)) {
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
        return "Input{"
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
