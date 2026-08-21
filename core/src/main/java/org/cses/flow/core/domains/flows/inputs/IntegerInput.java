package org.cses.flow.core.domains.flows.inputs;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

import java.util.Objects;

/**
 * Input definition for Integer values with optional inclusive bounds.
 */
@Getter
@Setter
@NoArgsConstructor
public final class IntegerInput extends Input<Integer> {

    private Integer min;
    private Integer max;

    @Builder
    public IntegerInput(
        String key,
        String displayName,
        boolean required,
        Integer defaultValue,
        Integer min,
        Integer max
    ) {
        setKey(key);
        setDisplayName(displayName);
        setRequired(required);
        setDefaultValue(defaultValue);
        this.min = min;
        this.max = max;
    }

    @Override
    protected void validateSubtypeDefinition() {
        if (min != null && max != null && min > max) {
            throw new IllegalArgumentException(
                "Input " + key() + " min must not exceed max"
            );
        }
    }

    @Override
    public DataType type() {
        return DataType.INTEGER;
    }

    @Override
    public void valid(Integer value) {
        validateRequired(value);
        if (value == null) {
            return;
        }
        if (min != null && value < min) {
            throw new IllegalArgumentException(
                "Input " + key() + " must be at least " + min
            );
        }
        if (max != null && value > max) {
            throw new IllegalArgumentException(
                "Input " + key() + " must be at most " + max
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
