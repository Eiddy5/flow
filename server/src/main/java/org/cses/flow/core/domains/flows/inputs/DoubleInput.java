package org.cses.flow.core.domains.flows.inputs;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.serde.annotation.Serdeable;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

/**
 * Input definition for finite Double values.
 */
@Serdeable
public final class DoubleInput extends Input<Double> {

    @JsonCreator
    DoubleInput(
        @JsonProperty("key") String key,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("required") boolean required,
        @JsonProperty("defaultValue") Double defaultValue
    ) {
        super(key, displayName, required, defaultValue);
        validateDefaultValue();
    }

    @Override
    public DataType getType() {
        return DataType.DOUBLE;
    }

    @Override
    public void valid(Double value) {
        validateRequired(value);
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(
                "Input " + getKey() + " must be finite"
            );
        }
    }
}
