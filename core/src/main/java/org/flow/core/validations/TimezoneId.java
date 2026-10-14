package org.flow.core.validations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.flow.core.validations.validator.TimezoneIdValidator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = TimezoneIdValidator.class)
public @interface TimezoneId {
    String message() default "invalid timezone ({validatedValue})";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}