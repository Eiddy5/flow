package org.cses.flow.core.domains.flows.inputs;

import io.micronaut.serde.annotation.Serdeable;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

/**
 * Input definition for finite Float values.
 */
@SuperBuilder
@NoArgsConstructor
@Serdeable
public final class FloatInput extends Input<Float> {

    @Override
    public DataType getType() {
        return DataType.FLOAT;
    }

    @Override
    public void valid(Float value) {
        validateRequired(value);
        if (value != null && !Float.isFinite(value)) {
            throw new IllegalArgumentException(
                "Input " + getKey() + " must be finite"
            );
        }
    }
}
