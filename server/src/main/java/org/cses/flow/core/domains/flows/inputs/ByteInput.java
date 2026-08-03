package org.cses.flow.core.domains.flows.inputs;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.serde.annotation.Serdeable;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

/**
 * Input definition for Byte values.
 */
@Serdeable
public final class ByteInput extends Input<Byte> {

    @JsonCreator
    ByteInput(
        @JsonProperty("key") String key,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("required") boolean required,
        @JsonProperty("defaultValue") Byte defaultValue
    ) {
        super(key, displayName, required, defaultValue);
        validateDefaultValue();
    }

    @Override
    public DataType getType() {
        return DataType.BYTE;
    }

    @Override
    public void valid(Byte value) {
        validateRequired(value);
    }
}
