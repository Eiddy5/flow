package org.cses.flow.core.domains.flows.inputs;

import io.micronaut.serde.annotation.Serdeable;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

/**
 * Input definition for Character values.
 */
@SuperBuilder
@NoArgsConstructor
@Serdeable
public final class CharacterInput extends Input<Character> {

    @Override
    public DataType getType() {
        return DataType.CHARACTER;
    }

    @Override
    public void valid(Character value) {
        validateRequired(value);
    }
}
