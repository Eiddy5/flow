package org.cses.flow.core.domains.conditions;

import org.cses.flow.core.domains.expressions.VariablePath;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class Operand {

    private VariablePath path;
    private Object value;

    private Operand(VariablePath path, Object value) {
        this.path = path;
        this.value = value;
    }

    public static Operand reference(List<String> path) {
        return new Operand(VariablePath.from(path), null);
    }

    public static Operand constant(Object value) {
        return new Operand(null, normalizeConstant(value));
    }

    public List<String> path() {
        return path == null ? List.of() : path.segments();
    }

    public Object value() {
        return value;
    }

    public String source() {
        if (isReference()) {
            return "{{ " + path.source() + " }}";
        }
        if (value instanceof String text) {
            return requiresQuotes(text)
                ? "\"" + escape(text) + "\""
                : text;
        }
        return value.toString();
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof Operand other)) {
            return false;
        }
        return Objects.equals(path, other.path)
            && Objects.equals(this.value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, value);
    }

    @Override
    public String toString() {
        return source();
    }

    boolean isReference() {
        return path != null;
    }

    Optional<Object> resolve(Map<String, ?> variables) {
        return path == null ? Optional.empty() : path.resolve(variables);
    }

    private static Object normalizeConstant(Object value) {
        if (value instanceof String || value instanceof Boolean
            || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                    "Unsupported Condition numeric constant: " + value,
                    exception
                );
            }
        }
        throw new IllegalArgumentException(
            "Condition constant must be String, Boolean, or Number"
        );
    }

    private static String escape(String source) {
        return source
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private static boolean requiresQuotes(String value) {
        if (value.isEmpty()
            || !value.equals(value.trim())
            || value.equals("true")
            || value.equals("false")
            || isDecimalSource(value)
            || value.startsWith("{{")
            || value.contains("&&")
            || value.contains("||")
            || value.indexOf('(') >= 0
            || value.indexOf(')') >= 0
            || value.indexOf('"') >= 0) {
            return true;
        }
        return value.chars().anyMatch(character ->
            character == '\n'
                || character == '\r'
                || character == '\t'
                || Character.isISOControl(character)
        );
    }

    static boolean isDecimalSource(String value) {
        int index = value.startsWith("-") ? 1 : 0;
        int integerStart = index;
        while (index < value.length()
            && Character.isDigit(value.charAt(index))) {
            index++;
        }
        if (integerStart == index) {
            return false;
        }
        if (index < value.length() && value.charAt(index) == '.') {
            index++;
            int fractionStart = index;
            while (index < value.length()
                && Character.isDigit(value.charAt(index))) {
                index++;
            }
            if (fractionStart == index) {
                return false;
            }
        }
        return index == value.length();
    }
}
