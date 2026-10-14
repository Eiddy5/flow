package org.flow.core.models.flows.input;

import java.time.LocalTime;

import org.flow.core.models.flows.Input;
import org.flow.core.models.validations.ManualConstraintViolation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.ConstraintViolationException;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Getter
@NoArgsConstructor
public class TimeInput extends Input<LocalTime> {
    @Schema(title = "Minimal value.")
    LocalTime after;

    @Schema(title = "Maximal value.")
    LocalTime before;

    @Override
    public void validate(LocalTime input) throws ConstraintViolationException {
        if (after != null && input.isBefore(after)) {
            throw ManualConstraintViolation.toConstraintViolationException(
                "it must be after `" + after + "`",
                this,
                TimeInput.class,
                getId(),
                input
            );
        }

        if (before != null && input.isAfter(before)) {
            throw ManualConstraintViolation.toConstraintViolationException(
                "it must be before `" + before + "`",
                this,
                TimeInput.class,
                getId(),
                input
            );
        }
    }
}
