package org.cses.flow.core.domains.flows.inputs;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.serde.annotation.Serdeable;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

import java.util.Objects;

/**
 * Input definition for Integer values with optional inclusive bounds.
 */
@Serdeable
public final class IntegerInput extends Input<Integer> {

    private final Integer min;
    private final Integer max;

    @JsonCreator
    IntegerInput(
        @JsonProperty("key") String key,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("required") boolean required,
        @JsonProperty("defaultValue") Integer defaultValue,
        @JsonProperty("min") Integer min,
        @JsonProperty("max") Integer max
    ) {
        super(key, displayName, required, defaultValue);
        if (min != null && max != null && min > max) {
            throw new IllegalArgumentException(
                "Input " + getKey() + " min must not exceed max"
            );
        }
        this.min = min;
        this.max = max;
        validateDefaultValue();
    }

    @Override
    public DataType getType() {
        return DataType.INTEGER;
    }

    public Integer getMin() {
        return min;
    }

    public Integer getMax() {
        return max;
    }

    @Override
    public void valid(Integer value) {
        validateRequired(value);
        if (value == null) {
            return;
        }
        if (min != null && value < min) {
            throw new IllegalArgumentException(
                "Input " + getKey() + " must be at least " + min
            );
        }
        if (max != null && value > max) {
            throw new IllegalArgumentException(
                "Input " + getKey() + " must be at most " + max
            );
        }
    }

    @Override
    protected boolean specificEquals(Input<?> other) {
        IntegerInput integerInput = (IntegerInput) other;
        return Objects.equals(min, integerInput.min)
            && Objects.equals(max, integerInput.max);
    }

    @Override
    protected int specificHashCode() {
        return Objects.hash(min, max);
    }
}
