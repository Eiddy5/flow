package org.flow.core.validations.validator;

import org.flow.core.models.triggers.multipleflows.MultipleCondition;
import org.flow.core.utils.ListUtils;
import org.flow.core.validations.FlowTriggerValidation;
import org.flow.plugin.core.trigger.Flow;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;
import jakarta.inject.Singleton;

@Singleton
public class FlowTriggerValidator implements ConstraintValidator<FlowTriggerValidation, Flow> {
    @Override
    public boolean isValid(@Nullable Flow value, @NonNull AnnotationValue<FlowTriggerValidation> annotationMetadata, @NonNull ConstraintValidatorContext context) {
        if (value == null) {
            return true; // nulls are allowed according to spec
        }

        if (MultipleCondition.Mode.AT_LEAST == value.getMode() && value.getMinSatisfied() == null) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("`minSatisfied` must be set when mode is AT_LEAST")
                .addConstraintViolation();
            return false;
        }

        if (value.getMinSatisfied() != null && value.getMinSatisfied() > ListUtils.emptyOnNull(value.getDependsOn()).size()) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("`minSatisfied` must be lower than or equal to the number of `dependsOn`")
                .addConstraintViolation();
            return false;
        }

        return true;
    }
}
