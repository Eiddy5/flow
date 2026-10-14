package org.flow.core.utils;

import java.util.regex.PatternSyntaxException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatternsTest {

    @Test
    void shouldReturnSameCompiledInstanceWhenSameRegex() {
        assertThat(Patterns.of("org\\.flow\\..*")).isSameAs(Patterns.of("org\\.flow\\..*"));
        assertThat(Patterns.of("org\\.flow\\..*").matcher("org.flow.plugin.core.log.Log").matches()).isTrue();
    }

    @Test
    void shouldThrowWhenRegexIsInvalid() {
        assertThatThrownBy(() -> Patterns.of("[unclosed")).isInstanceOf(PatternSyntaxException.class);
    }
}
