package org.flow.core.validations.validator;

import java.net.URI;

import org.flow.core.models.flows.input.FileInput;
import org.flow.core.models.property.Property;
import org.flow.core.models.property.PropertyContext;
import org.flow.core.runners.LocalPath;
import org.flow.core.runners.VariableRenderer;
import org.flow.core.validations.FileInputValidation;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
public class FileInputValidator implements ConstraintValidator<FileInputValidation, FileInput> {

    @Inject
    VariableRenderer variableRenderer;

    @Override
    public boolean isValid(@Nullable FileInput value, @NonNull AnnotationValue<FileInputValidation> annotationMetadata, @NonNull ConstraintValidatorContext context) {
        if (value == null) {
            return true; // nulls are allowed according to spec
        }

        if (value.getDefaults() != null) {
            PropertyContext propertyContext = PropertyContext.create(variableRenderer);
            try {
                URI uri = Property.as(value.getDefaults(), propertyContext, URI.class);
                if (uri != null && !LocalPath.FILE_SCHEME.equals(uri.getScheme())) {
                    context.disableDefaultConstraintViolation();
                    context
                        .buildConstraintViolationWithTemplate("inputs of type 'FILE' only support `defaults` as local files using a file URI")
                        .addConstraintViolation();
                    return false;
                }
            } catch (Exception ignore) {
                context.disableDefaultConstraintViolation();
                context
                    .buildConstraintViolationWithTemplate("inputs of type 'FILE' only support `defaults` with expression that can be rendered immediately")
                    .addConstraintViolation();
                return false;
            }
        }
        return true;
    }
}