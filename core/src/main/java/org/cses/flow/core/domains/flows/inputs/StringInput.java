package org.cses.flow.core.domains.flows.inputs;

import lombok.Builder;
import lombok.NoArgsConstructor;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

/**
 * Input definition for String values.
 */
@NoArgsConstructor
public final class StringInput extends Input<String> {

    @Builder
    public StringInput(
        String key,
        String displayName,
        boolean required,
        String defaultValue
    ) {
        setKey(key);
        setDisplayName(displayName);
        setRequired(required);
        setDefaultValue(defaultValue);
    }

    @Override
    public DataType type() {
        return DataType.STRING;
    }

    @Override
    public void valid(String value) {
        validateRequired(value);
    }
}
