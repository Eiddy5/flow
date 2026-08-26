package org.cses.flow.core.domains.flows.inputs;

import lombok.Builder;
import lombok.NoArgsConstructor;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

/**
 * Input definition for Boolean values.
 */
@NoArgsConstructor
public final class BooleanInput extends Input<Boolean> {

    @Builder
    public BooleanInput(
        String key,
        String displayName,
        boolean required,
        Boolean defaultValue
    ) {
        setKey(key);
        setDisplayName(displayName);
        setRequired(required);
        setDefaultValue(defaultValue);
    }

    @Override
    public DataType getType() {
        return DataType.BOOLEAN;
    }

    @Override
    public void valid(Boolean value) {
        validateRequired(value);
    }
}
