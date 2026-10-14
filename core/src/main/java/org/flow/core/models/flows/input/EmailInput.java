package org.flow.core.models.flows.input;

import java.util.regex.Pattern;

import org.flow.core.models.flows.Input;
import org.flow.core.models.validations.ManualConstraintViolation;

import jakarta.validation.ConstraintViolationException;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Getter
@NoArgsConstructor
public class EmailInput extends Input<String> {

    public static final String EMAIL_PATTERN = "^$|^[a-zA-Z0-9_!#$%&’*+/=?`{|}~^.-]+@[a-zA-Z0-9.-]+$";

    @Override
    public void validate(String input) throws ConstraintViolationException {
        if (!Pattern.matches(EMAIL_PATTERN, input)) {
            throw ManualConstraintViolation.toConstraintViolationException(
                "The input must be a valid email",
                this,
                EmailInput.class,
                getId(),
                input
            );
        }
    }
}
