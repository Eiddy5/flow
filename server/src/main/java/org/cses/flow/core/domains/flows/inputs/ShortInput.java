package org.cses.flow.core.domains.flows.inputs;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.serde.annotation.Serdeable;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

/**
 * Input definition for Short values.
 */
@Serdeable
public final class ShortInput extends Input<Short> {

    @JsonCreator
    ShortInput(
        @JsonProperty("key") String key,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("required") boolean required,
        @JsonProperty("defaultValue") Short defaultValue
    ) {
        super(key, displayName, required, defaultValue);
        validateDefaultValue();
    }

    @Override
    public DataType getType() {
        return DataType.SHORT;
    }

    @Override
    public void valid(Short value) {
        validateRequired(value);
    }
}
