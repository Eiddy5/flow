package org.cses.flow.core.domains.flows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;

/**
 * Stable value type shared by Flow and Task data definitions.
 */
public enum DataType {

    STRING(String.class),
    BOOLEAN(Boolean.class),
    BYTE(Byte.class),
    SHORT(Short.class),
    INTEGER(Integer.class),
    LONG(Long.class),
    FLOAT(Float.class),
    DOUBLE(Double.class),
    CHARACTER(Character.class);

    private final Class<?> valueClass;

    DataType(Class<?> valueClass) {
        this.valueClass = valueClass;
    }

    public static DataType parse(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException(
                "Data type must not be blank"
            );
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Unsupported Data type: " + code,
                exception
            );
        }
    }

    public boolean accepts(Object value) {
        return valueClass.isInstance(value);
    }

    /**
     * Converts one transport value to the exact Java wrapper represented by
     * this type. Numeric text is intentionally not accepted.
     */
    public Object normalize(Object value) {
        if (accepts(value)) {
            return value;
        }
        if (value == null) {
            throw invalidValue();
        }
        try {
            return switch (this) {
                case STRING, BOOLEAN -> throw invalidValue();
                case BYTE -> integer(value).byteValueExact();
                case SHORT -> integer(value).shortValueExact();
                case INTEGER -> integer(value).intValueExact();
                case LONG -> integer(value).longValueExact();
                case FLOAT -> finiteFloat(value);
                case DOUBLE -> finiteDouble(value);
                case CHARACTER -> character(value);
            };
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalidValue();
        }
    }

    public Class<?> getValueClass() {
        return valueClass;
    }

    private BigInteger integer(Object value) {
        if (!(value instanceof Number number)) {
            throw invalidValue();
        }
        return new BigDecimal(number.toString()).toBigIntegerExact();
    }

    private Float finiteFloat(Object value) {
        if (!(value instanceof Number number)) {
            throw invalidValue();
        }
        float result = number.floatValue();
        if (!Float.isFinite(result)) {
            throw invalidValue();
        }
        return result;
    }

    private Double finiteDouble(Object value) {
        if (!(value instanceof Number number)) {
            throw invalidValue();
        }
        double result = number.doubleValue();
        if (!Double.isFinite(result)) {
            throw invalidValue();
        }
        return result;
    }

    private Character character(Object value) {
        if (value instanceof String text && text.length() == 1) {
            return text.charAt(0);
        }
        throw invalidValue();
    }

    private IllegalArgumentException invalidValue() {
        return new IllegalArgumentException(
            "Value must be compatible with " + name()
        );
    }
}
