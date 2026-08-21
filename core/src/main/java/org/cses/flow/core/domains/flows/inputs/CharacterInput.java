package org.cses.flow.core.domains.flows.inputs;

import lombok.Builder;
import lombok.NoArgsConstructor;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

/**
 * Input definition for Character values.
 */
@NoArgsConstructor
public final class CharacterInput extends Input<Character> {

    @Builder
    public CharacterInput(
        String key,
        String displayName,
        boolean required,
        Character defaultValue
    ) {
        setKey(key);
        setDisplayName(displayName);
        setRequired(required);
        setDefaultValue(defaultValue);
    }

    @Override
    public DataType type() {
        return DataType.CHARACTER;
    }

    @Override
    public void valid(Character value) {
        validateRequired(value);
    }
}
