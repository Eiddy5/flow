package org.flow.core.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthUtilsTest {

    @Test
    void shouldReturnTrueForEqualStrings() {
        assertThat(AuthUtils.constantTimeEquals("admin@flow.io", "admin@flow.io")).isTrue();
    }

    @Test
    void shouldReturnFalseForDifferentStrings() {
        assertThat(AuthUtils.constantTimeEquals("admin@flow.io", "other@flow.io")).isFalse();
    }

    @Test
    void shouldReturnFalseForDifferentLengthStrings() {
        assertThat(AuthUtils.constantTimeEquals("admin@flow.io", "admin@flow.io.longer")).isFalse();
    }

    @Test
    void shouldReturnFalseWhenEitherArgumentIsNull() {
        assertThat(AuthUtils.constantTimeEquals(null, "admin@flow.io")).isFalse();
        assertThat(AuthUtils.constantTimeEquals("admin@flow.io", null)).isFalse();
        assertThat(AuthUtils.constantTimeEquals(null, null)).isFalse();
    }
}
