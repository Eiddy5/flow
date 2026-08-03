package org.cses.flow.core.domains.flows.inputs;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.serde.annotation.Serdeable;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

/**
 * Input definition for Character values.
 */
@Serdeable
public final class CharacterInput extends Input<Character> {

    @JsonCreator
    CharacterInput(
        @JsonProperty("key") String key,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("required") boolean required,
        @JsonProperty("defaultValue") Character defaultValue
    ) {
        super(key, displayName, required, defaultValue);
        validateDefaultValue();
    }

    @Override
    public DataType getType() {
        return DataType.CHARACTER;
    }

    @Override
    public void valid(Character value) {
        validateRequired(value);
    }
}
