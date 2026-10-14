package org.flow.core.validations;

import java.lang.annotation.*;

import org.flow.core.validations.validator.ScheduleValidator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ScheduleValidator.class)
public @interface ScheduleValidation {
    String message() default "invalid cron expression ({validatedValue.cron})";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}