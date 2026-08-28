package org.cses.flow.core.domains.conditions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public final class Operand {

    private final OperandScope scope;
    private final List<String> path;
    private final Object value;

    private Operand(
        OperandScope scope,
        List<String> path,
        Object value
    ) {
        this.scope = scope;
        this.path = path == null ? List.of() : List.copyOf(path);
        this.value = value;
    }

    public static Operand reference(
        OperandScope scope,
        List<String> path
    ) {
        Objects.requireNonNull(scope, "Operand scope");
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException(
                "Condition reference path must not be empty"
            );
        }
        for (String segment : path) {
            if (!isSafePathSegment(segment)) {
                throw new IllegalArgumentException(
                    "Condition reference path contains an invalid segment: "
                        + segment
                );
            }
        }
        return new Operand(scope, path, null);
    }

    public static Operand constant(Object value) {
        Object normalized = normalizeConstant(value);
        return new Operand(
            null,
            List.of(),
            normalized
        );
    }

    public OperandScope scope() {
        return scope;
    }

    public List<String> path() {
        return List.copyOf(path);
    }

    public Object value() {
        return value;
    }

    public String source() {
        if (isReference()) {
            return "{{ " + scope.source() + "."
                + String.join(".", path) + " }}";
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
        return scope == other.scope
            && Objects.equals(path, other.path)
            && Objects.equals(this.value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, path, value);
    }

    @Override
    public String toString() {
        return source();
    }

    boolean isReference() {
        return scope != null;
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

    private static boolean isSafePathSegment(String segment) {
        if (segment == null || segment.isEmpty()
            || !isAsciiLetter(segment.charAt(0))) {
            return false;
        }
        for (int index = 1; index < segment.length(); index++) {
            char value = segment.charAt(index);
            if (!isAsciiLetter(value)
                && (value < '0' || value > '9')
                && value != '_'
                && value != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetter(char value) {
        return value >= 'A' && value <= 'Z'
            || value >= 'a' && value <= 'z';
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
