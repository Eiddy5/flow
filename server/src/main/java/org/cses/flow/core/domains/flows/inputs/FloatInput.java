package org.cses.flow.core.domains.flows.inputs;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.serde.annotation.Serdeable;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

/**
 * Input definition for finite Float values.
 */
@Serdeable
public final class FloatInput extends Input<Float> {

    @JsonCreator
    FloatInput(
        @JsonProperty("key") String key,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("required") boolean required,
        @JsonProperty("defaultValue") Float defaultValue
    ) {
        super(key, displayName, required, defaultValue);
        validateDefaultValue();
    }

    @Override
    public DataType getType() {
        return DataType.FLOAT;
    }

    @Override
    public void valid(Float value) {
        validateRequired(value);
        if (value != null && !Float.isFinite(value)) {
            throw new IllegalArgumentException(
                "Input " + getKey() + " must be finite"
            );
        }
    }
}
