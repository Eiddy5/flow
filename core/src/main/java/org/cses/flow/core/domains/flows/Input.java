package org.cses.flow.core.domains.flows;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.annotation.JsonTypeIdResolver;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.cses.flow.core.domains.flows.inputs.BooleanInput;
import org.cses.flow.core.domains.flows.inputs.ByteInput;
import org.cses.flow.core.domains.flows.inputs.CharacterInput;
import org.cses.flow.core.domains.flows.inputs.DoubleInput;
import org.cses.flow.core.domains.flows.inputs.FloatInput;
import org.cses.flow.core.domains.flows.inputs.IntegerInput;
import org.cses.flow.core.domains.flows.inputs.LongInput;
import org.cses.flow.core.domains.flows.inputs.ShortInput;
import org.cses.flow.core.domains.flows.inputs.StringInput;
import org.paas.json.SerializableObject;

import java.util.Objects;

/**
 * Base description of one Flow or Task input.
 *
 * @param <T> accepted Java wrapper value type
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type"
)
@JsonTypeIdResolver(InputTypeIdResolver.class)
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
public abstract class Input<T> extends SerializableObject implements Data {

    private String key;
    private String displayName;
    private boolean required;
    private T defaultValue;

    /**
     * Keeps PAAS JSON strict even when its default Jackson mapper is lenient.
     */
    @JsonAnySetter
    private void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException(
            "Unsupported Input field: " + field
        );
    }

    /**
     * Completes validation after JSON no-args construction and setter binding.
     */
    public final void validateDefinition() {
        key = requireText(key, "Input key");
        displayName = displayName == null
            ? key
            : requireText(displayName, "Input displayName");
        validateSubtypeDefinition();
        validateDefaultValue();
    }

    /**
     * Validates one typed value against subtype-specific rules.
     */
    public abstract void valid(T value);

    /**
     * Normalizes one transport value to this Input's exact Java type and then
     * applies the concrete Input rules.
     */
    @SuppressWarnings("unchecked")
    public final Object normalized(Object value) {
        T normalized = value == null
            ? null
            : (T) getType().normalize(value);
        valid(normalized);
        return normalized;
    }

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

    protected void validateSubtypeDefinition() {
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
