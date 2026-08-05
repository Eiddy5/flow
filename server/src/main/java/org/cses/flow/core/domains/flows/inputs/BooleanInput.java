package org.cses.flow.core.domains.flows.inputs;

import io.micronaut.serde.annotation.Serdeable;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

/**
 * Input definition for Boolean values.
 */
@SuperBuilder
@NoArgsConstructor
@Serdeable
public final class BooleanInput extends Input<Boolean> {

    @Override
    public DataType getType() {
        return DataType.BOOLEAN;
    }

    @Override
    public void valid(Boolean value) {
        validateRequired(value);
    }
}
