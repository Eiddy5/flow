package org.cses.flow.core.domains.flows.inputs;

import io.micronaut.serde.annotation.Serdeable;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

/**
 * Input definition for finite Double values.
 */
@SuperBuilder
@NoArgsConstructor
@Serdeable
public final class DoubleInput extends Input<Double> {

    @Override
    public DataType getType() {
        return DataType.DOUBLE;
    }

    @Override
    public void valid(Double value) {
        validateRequired(value);
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(
                "Input " + getKey() + " must be finite"
            );
        }
    }
}
