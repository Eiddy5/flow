package com.example.flow.inputs;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;

import java.util.Locale;
import java.util.Objects;

/** Host-owned input: trims and uppercases a code, then requires PREFIX-digits. */
@JsonTypeName("BUSINESS_CODE")
public class BusinessCodeInput extends Input<String> {

    private String prefix;

    /**
     * Creates a complete definition after storing its prefix and validating the default.
     * @param key nonblank field key
     * @param displayName optional display name, null uses the key
     * @param required whether binding requires a nonnull result
     * @param defaultValue optional unnormalized string default
     * @param prefix two to eight uppercase ASCII letters
     * @throws IllegalArgumentException when configuration or default violates the code rule
     */
    private BusinessCodeInput(String key, String displayName, boolean required,
        String defaultValue, String prefix) {
        super(key, displayName, required, defaultValue);
        this.prefix = prefix;
        validateDefinition();
    }

    /**
     * Creates a validated business definition from JSON or YAML fields without a no-arg object.
     * @param key raw nonblank string key; other types are rejected
     * @param displayName optional display name
     * @param required optional required flag, null means false
     * @param defaultValue optional raw string default; nonstring values are rejected
     * @param prefix required uppercase code prefix
     * @return complete immutable business input definition
     * @throws IllegalArgumentException when field types, prefix or default are invalid
     */
    @JsonCreator
    public static BusinessCodeInput from(
        @JsonProperty("key") Object key,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("required") Boolean required,
        @JsonProperty("defaultValue") Object defaultValue,
        @JsonProperty("prefix") String prefix
    ) {
        return new BusinessCodeInput(definitionKey(key), displayName,
            Boolean.TRUE.equals(required), defaultValue == null ? null
                : (String) DataType.STRING.normalize(defaultValue), prefix);
    }

    /** @return the configured prefix, retained independently for each input field */
    public String getPrefix() {
        return prefix;
    }

    /** @return STRING, the underlying value type of a business code */
    @Override
    public DataType getValueType() {
        return DataType.STRING;
    }

    /**
     * Normalizes a submitted string before checking its business rule.
     * @param value nonnull transport value, never modified
     * @return trimmed uppercase code
     * @throws IllegalArgumentException when the transport value is not a string
     */
    @Override
    protected String convert(Object value) {
        return ((String) DataType.STRING.normalize(value)).trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Rejects a code whose prefix or numeric suffix differs from this definition.
     * @param value normalized nonnull code
     * @throws IllegalArgumentException when the code is not PREFIX-digits
     */
    @Override
    protected void validateValue(String value) {
        if (!value.matches(prefix + "-[0-9]+")) {
            throw new IllegalArgumentException("Business code must match " + prefix + "-digits");
        }
    }

    /** @throws IllegalArgumentException when the prefix is not two to eight uppercase letters */
    @Override
    protected void validateSubtypeDefinition() {
        if (prefix == null || !prefix.matches("[A-Z]{2,8}")) {
            throw new IllegalArgumentException("Business code prefix must contain 2 to 8 uppercase letters");
        }
    }

    /**
     * Compares the configured prefix after the base class checks concrete type and common fields.
     * @param other another BusinessCodeInput, not modified
     * @return whether both definitions have the same prefix
     */
    @Override
    protected boolean specificEquals(Input<?> other) {
        return Objects.equals(prefix, ((BusinessCodeInput) other).prefix);
    }

    /** @return the hash contribution of this definition's prefix */
    @Override
    protected int specificHashCode() {
        return Objects.hashCode(prefix);
    }
}
