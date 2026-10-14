package org.flow.core.validations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.flow.core.validations.validator.FilesVersionBehaviorValidator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = FilesVersionBehaviorValidator.class)
public @interface FilesVersionBehaviorValidation {
    String message() default "invalid `version` behavior configuration";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
