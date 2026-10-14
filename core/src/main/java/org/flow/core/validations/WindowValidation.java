package org.flow.core.validations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.flow.core.validations.validator.WindowValidator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = WindowValidator.class)
public @interface WindowValidation {
    String message() default "invalid window definition";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}