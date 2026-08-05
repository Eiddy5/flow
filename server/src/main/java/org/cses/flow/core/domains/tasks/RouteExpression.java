package org.cses.flow.core.domains.tasks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * First-phase route expression used by a child Task.
 */
public final class RouteExpression {

    private static final String DIRECT = "DIRECT";
    private static final Pattern OUTPUT_EQUALS = Pattern.compile(
        "^outputs\\.([A-Za-z][A-Za-z0-9_-]*)\\s*==\\s*\"([^\"]*)\"$"
    );

    private final String source;
    private final String outputName;
    private final String expectedValue;

    private RouteExpression(
        String source,
        String outputName,
        String expectedValue
    ) {
        this.source = source;
        this.outputName = outputName;
        this.expectedValue = expectedValue;
    }

    public static RouteExpression direct() {
        return new RouteExpression(DIRECT, null, null);
    }

    @JsonCreator
    public static RouteExpression parse(String expression) {
        String normalized = expression == null || expression.isBlank()
            ? DIRECT
            : expression.trim();
        if (DIRECT.equals(normalized)) {
            return direct();
        }
        Matcher matcher = OUTPUT_EQUALS.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                "Unsupported Task route expression: " + normalized
            );
        }
        return new RouteExpression(
            normalized,
            matcher.group(1),
            matcher.group(2)
        );
    }

    @JsonValue
    public String source() {
        return source;
    }

    public Optional<String> referencedOutputKey() {
        return Optional.ofNullable(outputName);
    }

    public boolean matches(Map<String, ?> outputs) {
        if (outputName == null) {
            return true;
        }
        Object actual = outputs == null ? null : outputs.get(outputName);
        return actual instanceof String
            && expectedValue.equals(actual);
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof RouteExpression other)) {
            return false;
        }
        return Objects.equals(source, other.source)
            && Objects.equals(outputName, other.outputName)
            && Objects.equals(expectedValue, other.expectedValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, outputName, expectedValue);
    }

    @Override
    public String toString() {
        return source;
    }
}
