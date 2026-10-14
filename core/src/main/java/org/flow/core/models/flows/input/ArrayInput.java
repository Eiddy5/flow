package org.flow.core.models.flows.input;

import java.util.List;

import org.flow.core.models.flows.Input;
import org.flow.core.models.flows.Type;
import org.flow.core.validations.ArrayInputValidation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Getter
@NoArgsConstructor
@ArrayInputValidation
public class ArrayInput extends Input<List<?>> implements ItemTypeInterface {
    @Schema(
        title = "Type of the array items.",
        description = "Cannot be of type `ARRAY`."
    )
    @NotNull
    private Type itemType;

    @Override
    public void validate(List<?> input) throws ConstraintViolationException {
        // no validation yet
    }
}
