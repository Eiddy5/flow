package org.cses.flow.core.domains.flows.inputs;

import lombok.Builder;
import lombok.NoArgsConstructor;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

/**
 * Input definition for finite Float values.
 */
@NoArgsConstructor
public final class FloatInput extends Input<Float> {

    @Builder
    public FloatInput(
        String key,
        String displayName,
        boolean required,
        Float defaultValue
    ) {
        setKey(key);
        setDisplayName(displayName);
        setRequired(required);
        setDefaultValue(defaultValue);
    }

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
