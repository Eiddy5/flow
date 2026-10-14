package org.flow.core.validations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.flow.core.validations.validator.TimeWindowValidator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = TimeWindowValidator.class)
public @interface TimeWindowValidation {
    String message() default "invalid time window definition";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
