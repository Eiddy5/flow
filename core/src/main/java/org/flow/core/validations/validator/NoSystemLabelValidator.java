package org.flow.core.validations.validator;

import org.flow.core.models.Label;
import org.flow.core.validations.NoSystemLabelValidation;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;
import jakarta.inject.Singleton;

@Singleton
public class NoSystemLabelValidator implements ConstraintValidator<NoSystemLabelValidation, Label> {
    @Override
    public boolean isValid(@Nullable Label value, @NonNull AnnotationValue<NoSystemLabelValidation> annotationMetadata, @NonNull ConstraintValidatorContext context) {
        if (value == null) {
            return true; // nulls are allowed according to spec
        }

        if (value.key().startsWith(Label.SYSTEM_PREFIX)) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("System labels can only be set by Flow itself, offending label: " + value.key() + "=" + value.value() + ".")
                .addConstraintViolation();
            return false;
        }

        return true;
    }
}
