package org.cses.flow.core.utils;

import java.util.Objects;

/**
 * Returns required values after validating that they are present.
 */
public final class RequiredUtil {

    private RequiredUtil() {
    }

    public static <T> T required(T value, String message) {
        return Objects.requireNonNull(value, message);
    }

    public static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
