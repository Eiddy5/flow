package org.cses.flow.core.domains.flows.inputs;

import lombok.Builder;
import lombok.NoArgsConstructor;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

/**
 * Input definition for Long values.
 */
@NoArgsConstructor
public final class LongInput extends Input<Long> {

    @Builder
    public LongInput(
        String key,
        String displayName,
        boolean required,
        Long defaultValue
    ) {
        setKey(key);
        setDisplayName(displayName);
        setRequired(required);
        setDefaultValue(defaultValue);
    }

    @Override
    public DataType type() {
        return DataType.LONG;
    }

    @Override
    public void valid(Long value) {
        validateRequired(value);
    }
}
