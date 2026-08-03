package org.cses.flow.core.domains.flows;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.micronaut.serde.annotation.Serdeable;
import org.cses.flow.core.domains.flows.inputs.BooleanInput;
import org.cses.flow.core.domains.flows.inputs.ByteInput;
import org.cses.flow.core.domains.flows.inputs.CharacterInput;
import org.cses.flow.core.domains.flows.inputs.DoubleInput;
import org.cses.flow.core.domains.flows.inputs.FloatInput;
import org.cses.flow.core.domains.flows.inputs.IntegerInput;
import org.cses.flow.core.domains.flows.inputs.LongInput;
import org.cses.flow.core.domains.flows.inputs.ShortInput;
import org.cses.flow.core.domains.flows.inputs.StringInput;

import java.util.Objects;

/**
 * Immutable base description of one Flow or Task input.
 *
 * @param <T> accepted Java wrapper value type
 */
@Serdeable
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = StringInput.class, name = "STRING"),
    @JsonSubTypes.Type(value = BooleanInput.class, name = "BOOLEAN"),
    @JsonSubTypes.Type(value = ByteInput.class, name = "BYTE"),
    @JsonSubTypes.Type(value = ShortInput.class, name = "SHORT"),
    @JsonSubTypes.Type(value = IntegerInput.class, name = "INTEGER"),
    @JsonSubTypes.Type(value = LongInput.class, name = "LONG"),
    @JsonSubTypes.Type(value = FloatInput.class, name = "FLOAT"),
    @JsonSubTypes.Type(value = DoubleInput.class, name = "DOUBLE"),
    @JsonSubTypes.Type(value = CharacterInput.class, name = "CHARACTER")
})
public abstract class Input<T> implements Data {

    private final String key;
    private final String displayName;
    private final boolean required;
    private final T defaultValue;

    protected Input(
        String key,
        String displayName,
        boolean required,
        T defaultValue
    ) {
        this.key = requireText(key, "Input key");
        this.displayName = requireText(
            displayName,
            "Input displayName"
        );
        this.required = required;
        this.defaultValue = defaultValue;
    }

    @Override
    public final String getKey() {
        return key;
    }

    public final String getDisplayName() {
        return displayName;
    }

    public final boolean isRequired() {
        return required;
    }

    public final T getDefaultValue() {
        return defaultValue;
    }

    /**
     * Validates one typed value against subtype-specific rules.
     */
    public abstract void valid(T value);

    protected final void validateRequired(T value) {
        if (required && value == null) {
            throw new IllegalArgumentException(
                "Input " + key + " is required"
            );
        }
    }

    protected final void validateDefaultValue() {
        if (defaultValue != null) {
            valid(defaultValue);
        }
    }

    protected boolean specificEquals(Input<?> other) {
        return true;
    }

    protected int specificHashCode() {
        return 0;
    }

    @Override
    public final boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof Input<?> other)) {
            return false;
        }
        return getClass().equals(other.getClass())
            && required == other.required
            && Objects.equals(key, other.key)
            && Objects.equals(displayName, other.displayName)
            && Objects.equals(defaultValue, other.defaultValue)
            && specificEquals(other);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(
            getClass(),
            key,
            displayName,
            required,
            defaultValue,
            specificHashCode()
        );
    }

    @Override
    public final String toString() {
        return getClass().getSimpleName()
            + "{key='" + key + '\''
            + ", displayName='" + displayName + '\''
            + ", required=" + required
            + ", defaultValue=" + defaultValue
            + '}';
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                field + " must not be blank"
            );
        }
        return value.trim();
    }
}
