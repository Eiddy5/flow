package org.cses.flow.core.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RequiredUtilTest {

    @Test
    void returnsTheSameRequiredObject() {
        Object value = new Object();

        assertSame(value, RequiredUtil.required(value, "value is required"));
    }

    @Test
    void rejectsNullObjectThroughObjectsUtility() {
        NullPointerException error = assertThrows(
            NullPointerException.class,
            () -> RequiredUtil.required(
                (Object) null,
                "value is required"
            )
        );

        assertEquals("value is required", error.getMessage());
    }

    @Test
    void returnsRequiredStringWithoutNormalizingIt() {
        String value = "  value  ";

        assertSame(value, RequiredUtil.required(value, "value is required"));
    }

    @Test
    void rejectsNullEmptyAndBlankStrings() {
        assertRequiredStringFailure(null);
        assertRequiredStringFailure("");
        assertRequiredStringFailure("   ");
    }

    private static void assertRequiredStringFailure(String value) {
        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> RequiredUtil.required(value, "value is required")
        );

        assertEquals("value is required", error.getMessage());
    }
}
