package org.flow.core.validations;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;
import io.micronaut.core.annotation.Introspected;
import org.flow.core.models.validations.ModelValidator;

import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolationException;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class SafeRegexValidationTest {
    @Inject
    private ModelValidator modelValidator;

    @Introspected
    record PatternValue(@SafeRegexValidation String value) {
    }

    @Test
    void shouldValidateSafeRegex() {
        // Given
        PatternValue filter = new PatternValue("org\\.flow\\..*");

        // When
        Optional<ConstraintViolationException> valid = modelValidator.isValid(filter);

        // Then
        assertThat(valid.isEmpty()).isTrue();
    }

    @Test
    void shouldNotValidateCatastrophicRegex() {
        // Given
        PatternValue filter = new PatternValue("(a+)+");

        // When
        Optional<ConstraintViolationException> valid = modelValidator.isValid(filter);

        // Then
        assertThat(valid.isPresent()).isTrue();
        assertThat(valid.get().getMessage()).contains("catastrophic backtracking");
    }

    @Test
    void shouldNotValidateInvalidRegexSyntax() {
        // Given
        PatternValue filter = new PatternValue("[a-");

        // When
        Optional<ConstraintViolationException> valid = modelValidator.isValid(filter);

        // Then
        assertThat(valid.isPresent()).isTrue();
        assertThat(valid.get().getMessage()).contains("invalid regex pattern");
        assertThat(valid.get().getMessage()).contains("[a-");
    }
}
