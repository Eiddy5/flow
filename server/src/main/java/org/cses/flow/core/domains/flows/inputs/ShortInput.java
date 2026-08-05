package org.cses.flow.core.domains.flows.inputs;

import io.micronaut.serde.annotation.Serdeable;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

/**
 * Input definition for Short values.
 */
@SuperBuilder
@NoArgsConstructor
@Serdeable
public final class ShortInput extends Input<Short> {

    @Override
    public DataType getType() {
        return DataType.SHORT;
    }

    @Override
    public void valid(Short value) {
        validateRequired(value);
    }
}
