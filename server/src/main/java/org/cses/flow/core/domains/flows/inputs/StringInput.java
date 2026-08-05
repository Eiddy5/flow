package org.cses.flow.core.domains.flows.inputs;

import io.micronaut.serde.annotation.Serdeable;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

/**
 * Input definition for String values.
 */
@SuperBuilder
@NoArgsConstructor
@Serdeable
public final class StringInput extends Input<String> {

    @Override
    public DataType getType() {
        return DataType.STRING;
    }

    @Override
    public void valid(String value) {
        validateRequired(value);
    }
}
