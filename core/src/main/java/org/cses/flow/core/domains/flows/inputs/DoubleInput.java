package org.cses.flow.core.domains.flows.inputs;

import lombok.Builder;
import lombok.NoArgsConstructor;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

/**
 * Input definition for finite Double values.
 */
@NoArgsConstructor
public final class DoubleInput extends Input<Double> {

    @Builder
    public DoubleInput(
        String key,
        String displayName,
        boolean required,
        Double defaultValue
    ) {
        setKey(key);
        setDisplayName(displayName);
        setRequired(required);
        setDefaultValue(defaultValue);
    }

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
