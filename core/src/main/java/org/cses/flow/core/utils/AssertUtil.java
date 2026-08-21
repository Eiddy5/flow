package org.cses.flow.core.utils;


import org.paas.exception.DataException;

import java.util.List;
import java.util.Objects;

public class AssertUtil {

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new DataException(message);
        }
    }

    public static void assertNotEquals(Object expected, Object actual, String message) {
        if (Objects.equals(expected, actual)) {
            throw new DataException(message);
        }
    }

    public static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new DataException(message);
        }
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new DataException(message);
        }
    }

    public static void assertNotNull(Object object, String message) {
        if (object == null) {
            throw new DataException(message);
        }
    }

    public static void assertNull(Object object, String message) {
        if (object != null) {
            throw new DataException(message);
        }
    }

    public static void assertNotEmpty(List<?> list, String message) {
        if (list == null || list.isEmpty()) {
            throw new DataException(message);
        }
    }

    public static void assertNotBlank(String name, String message) {
        if (name == null || name.isBlank()) {
            throw new DataException(message);
        }
    }
}
