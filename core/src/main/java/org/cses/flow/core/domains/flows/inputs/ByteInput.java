package org.cses.flow.core.domains.flows.inputs;

import lombok.Builder;
import lombok.NoArgsConstructor;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

/**
 * Input definition for Byte values.
 */
@NoArgsConstructor
public final class ByteInput extends Input<Byte> {

    @Builder
    public ByteInput(
        String key,
        String displayName,
        boolean required,
        Byte defaultValue
    ) {
        setKey(key);
        setDisplayName(displayName);
        setRequired(required);
        setDefaultValue(defaultValue);
    }

    @Override
    public DataType type() {
        return DataType.BYTE;
    }

    @Override
    public void valid(Byte value) {
        validateRequired(value);
    }
}
