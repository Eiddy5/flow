package org.cses.flow.core.domains.flows.inputs;

import lombok.Builder;
import lombok.NoArgsConstructor;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

/**
 * Input definition for Short values.
 */
@NoArgsConstructor
public final class ShortInput extends Input<Short> {

    @Builder
    public ShortInput(
        String key,
        String displayName,
        boolean required,
        Short defaultValue
    ) {
        setKey(key);
        setDisplayName(displayName);
        setRequired(required);
        setDefaultValue(defaultValue);
    }

    @Override
    public DataType type() {
        return DataType.SHORT;
    }

    @Override
    public void valid(Short value) {
        validateRequired(value);
    }
}
