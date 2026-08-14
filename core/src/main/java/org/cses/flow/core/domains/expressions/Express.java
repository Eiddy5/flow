package org.cses.flow.core.domains.expressions;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.cses.flow.core.domains.flows.DataType;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable, restricted condition over Flow outputs, confirmed inputs, or
 * Flow-level variables.
 */
public final class Express {

    private static final Pattern CONDITION = Pattern.compile(
        "^(outputs|inputs|variables)\\.([A-Za-z][A-Za-z0-9_-]*"
            + "(?:\\.[A-Za-z][A-Za-z0-9_-]*)*)"
            + "\\s*(==|!=|>=|<=|>|<)\\s*"
            + "(\"(?:\\\\.|[^\"\\\\])*\"|true|false|-?\\d+(?:\\.\\d+)?)$"
    );

    private final String source;
    private final Root root;
    private final List<String> outputPath;
    private final Operator operator;
    private final Object expectedValue;

    private Express(
        String source,
        Root root,
        List<String> outputPath,
        Operator operator,
        Object expectedValue
    ) {
        this.source = source;
        this.root = root;
        this.outputPath = List.copyOf(outputPath);
        this.operator = operator;
        this.expectedValue = expectedValue;
    }

    @JsonCreator
    public static Express parse(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException(
                "Express condition must not be blank"
            );
        }
        String normalized = source.trim();
        Matcher matcher = CONDITION.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                "Unsupported Express condition: " + normalized
            );
        }
        return new Express(
            normalized,
            Root.valueOf(matcher.group(1).toUpperCase()),
            List.of(matcher.group(2).split("\\.")),
            Operator.parse(matcher.group(3)),
            parseLiteral(matcher.group(4))
        );
    }

    @JsonValue
    public String source() {
        return source;
    }

    public List<String> outputPath() {
        return outputPath;
    }

    public boolean referencesOutputs() {
        return root == Root.OUTPUTS;
    }

    public boolean referencesInputs() {
        return root == Root.INPUTS;
    }

    public boolean referencesVariables() {
        return root == Root.VARIABLES;
    }

    public String referencedKey() {
        return outputPath.getFirst();
    }

    public String rootName() {
        return root.name().toLowerCase();
    }

    public String operatorSource() {
        return operator.source;
    }

    public Object expectedValue() {
        return expectedValue;
    }

    public boolean supportsOrderedComparison() {
        return operator.ordered;
    }

    public boolean supports(DataType type) {
        Objects.requireNonNull(type, "Data type");
        boolean compatible = switch (type) {
            case STRING -> expectedValue instanceof String;
            case CHARACTER -> expectedValue instanceof String text
                && text.length() == 1;
            case BOOLEAN -> expectedValue instanceof Boolean;
            case BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE ->
                expectedValue instanceof BigDecimal;
        };
        return compatible && (!operator.ordered || isNumeric(type));
    }

    public boolean matches(Map<String, ?> outputs) {
        return matches(outputs, Map.of(), Map.of());
    }

    public boolean matches(
        Map<String, ?> outputs,
        Map<String, ?> inputs
    ) {
        return matches(outputs, inputs, Map.of());
    }

    public boolean matches(
        Map<String, ?> outputs,
        Map<String, ?> inputs,
        Map<String, ?> variables
    ) {
        Object actual = switch (root) {
            case OUTPUTS -> outputs;
            case INPUTS -> inputs;
            case VARIABLES -> variables;
        };
        for (String segment : outputPath) {
            if (!(actual instanceof Map<?, ?> values)) {
                return false;
            }
            actual = values.get(segment);
        }
        return compare(actual);
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof Express other)) {
            return false;
        }
        return Objects.equals(source, other.source)
            && root == other.root
            && Objects.equals(outputPath, other.outputPath)
            && operator == other.operator
            && Objects.equals(expectedValue, other.expectedValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            source,
            root,
            outputPath,
            operator,
            expectedValue
        );
    }

    @Override
    public String toString() {
        return source;
    }

    private boolean compare(Object actual) {
        if (actual == null) {
            return false;
        }
        int comparison;
        if (actual instanceof Number number
            && expectedValue instanceof BigDecimal expectedNumber) {
            comparison = new BigDecimal(number.toString())
                .compareTo(expectedNumber);
        } else if (actual instanceof String actualString
            && expectedValue instanceof String expectedString) {
            comparison = actualString.compareTo(expectedString);
        } else if (actual instanceof Character character
            && expectedValue instanceof String expectedString) {
            comparison = character.toString().compareTo(expectedString);
        } else if (actual instanceof Boolean actualBoolean
            && expectedValue instanceof Boolean expectedBoolean) {
            comparison = actualBoolean.compareTo(expectedBoolean);
        } else {
            return false;
        }
        return operator.matches(comparison);
    }

    private static Object parseLiteral(String source) {
        if (source.startsWith("\"")) {
            return unescape(source.substring(1, source.length() - 1));
        }
        if ("true".equals(source) || "false".equals(source)) {
            return Boolean.valueOf(source);
        }
        return new BigDecimal(source);
    }

    private static boolean isNumeric(DataType type) {
        return switch (type) {
            case BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE -> true;
            case STRING, BOOLEAN, CHARACTER -> false;
        };
    }

    private static String unescape(String source) {
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (!escaped) {
                if (current == '\\') {
                    escaped = true;
                } else {
                    value.append(current);
                }
                continue;
            }
            value.append(switch (current) {
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case '\\' -> '\\';
                case '"' -> '"';
                default -> current;
            });
            escaped = false;
        }
        if (escaped) {
            throw new IllegalArgumentException(
                "Express string literal has an incomplete escape"
            );
        }
        return value.toString();
    }

    private enum Root {
        OUTPUTS,
        INPUTS,
        VARIABLES
    }

    private enum Operator {
        EQUALS("==", false),
        NOT_EQUALS("!=", false),
        GREATER_THAN(">", true),
        GREATER_THAN_OR_EQUALS(">=", true),
        LESS_THAN("<", true),
        LESS_THAN_OR_EQUALS("<=", true);

        private final String source;
        private final boolean ordered;

        Operator(String source, boolean ordered) {
            this.source = source;
            this.ordered = ordered;
        }

        private static Operator parse(String source) {
            return Arrays.stream(values())
                .filter(candidate -> candidate.source.equals(source))
                .findFirst()
                .orElseThrow();
        }

        private boolean matches(int comparison) {
            return switch (this) {
                case EQUALS -> comparison == 0;
                case NOT_EQUALS -> comparison != 0;
                case GREATER_THAN -> comparison > 0;
                case GREATER_THAN_OR_EQUALS -> comparison >= 0;
                case LESS_THAN -> comparison < 0;
                case LESS_THAN_OR_EQUALS -> comparison <= 0;
            };
        }
    }
}
