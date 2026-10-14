package org.flow.core.validations;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.triggers.TimeWindow;
import org.flow.core.models.validations.ModelValidator;

import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolationException;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class DurationMaxValidationTest {
    @Inject
    private ModelValidator modelValidator;

    @Test
    void shouldAcceptWindowWithinTenYears() {
        TimeWindow window = TimeWindow.builder()
            .window(Duration.ofDays(30))
            .build();

        assertThat(modelValidator.isValid(window).isEmpty()).isTrue();
    }

    @Test
    void shouldRejectWindowAboveTenYears() {
        TimeWindow window = TimeWindow.builder()
            .window(Duration.ofDays(3651))
            .build();

        Optional<ConstraintViolationException> valid = modelValidator.isValid(window);
        assertThat(valid.isEmpty()).isFalse();
        assertThat(valid.get().getMessage()).contains("must not exceed P3650D");
    }

    @Test
    void shouldRejectWindowAdvanceAboveTenYears() {
        TimeWindow window = TimeWindow.builder()
            .window(Duration.ofDays(1))
            .windowAdvance(Duration.ofDays(3651))
            .build();

        Optional<ConstraintViolationException> valid = modelValidator.isValid(window);
        assertThat(valid.isEmpty()).isFalse();
        assertThat(valid.get().getMessage()).contains("must not exceed P3650D");
    }
}
