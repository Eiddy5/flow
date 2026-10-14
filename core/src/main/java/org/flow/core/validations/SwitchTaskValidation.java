package org.flow.core.validations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.flow.core.validations.validator.SwitchTaskValidator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SwitchTaskValidator.class)
public @interface SwitchTaskValidation {
    String message() default "invalid Switch task";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
