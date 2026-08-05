package org.cses.flow.core.domains.flows.inputs;

import io.micronaut.serde.annotation.Serdeable;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

/**
 * Input definition for Byte values.
 */
@SuperBuilder
@NoArgsConstructor
@Serdeable
public final class ByteInput extends Input<Byte> {

    @Override
    public DataType getType() {
        return DataType.BYTE;
    }

    @Override
    public void valid(Byte value) {
        validateRequired(value);
    }
}
