package org.cses.flow.core.domains.flows.inputs;

import io.micronaut.serde.annotation.Serdeable;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

/**
 * Input definition for Long values.
 */
@SuperBuilder
@NoArgsConstructor
@Serdeable
public final class LongInput extends Input<Long> {

    @Override
    public DataType getType() {
        return DataType.LONG;
    }

    @Override
    public void valid(Long value) {
        validateRequired(value);
    }
}
