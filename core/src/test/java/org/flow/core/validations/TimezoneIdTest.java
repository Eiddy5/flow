package org.flow.core.validations;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.validations.ModelValidator;

import jakarta.inject.Inject;
import lombok.AllArgsConstructor;
import lombok.Getter;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class TimezoneIdTest {
    @Inject
    private ModelValidator modelValidator;

    @AllArgsConstructor
    @Getter
    public static class TimezoneIdCls {
        @TimezoneId
        String timezone;
    }

    @Test
    void inputValidation() {
        final TimezoneIdCls existingTimezone = new TimezoneIdCls("Europe/Paris");

        assertThat(modelValidator.isValid(existingTimezone).isEmpty()).isTrue();

        final TimezoneIdCls invalidTimezone = new TimezoneIdCls("Foo/Bar");

        assertThat(modelValidator.isValid(invalidTimezone).isPresent()).isTrue();
        assertThat(modelValidator.isValid(invalidTimezone).get().getMessage())
            .satisfies(
                arg -> assertThat(arg).startsWith("timezone"),
                arg -> assertThat(arg).contains("is not a valid time-zone ID")
            );
    }
}
