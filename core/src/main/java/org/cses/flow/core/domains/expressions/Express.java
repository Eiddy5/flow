package org.cses.flow.core.domains.expressions;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable, restricted condition over Flow outputs.
 */
public final class Express {

    private static final Pattern OUTPUT_EQUALS = Pattern.compile(
        "^outputs\\.([A-Za-z][A-Za-z0-9_-]*"
            + "(?:\\.[A-Za-z][A-Za-z0-9_-]*)*)"
            + "\\s*==\\s*\"([^\"]*)\"$"
    );

    private final String source;
    private final List<String> outputPath;
    private final String expectedValue;

    private Express(
        String source,
        List<String> outputPath,
        String expectedValue
    ) {
        this.source = source;
        this.outputPath = List.copyOf(outputPath);
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
        Matcher matcher = OUTPUT_EQUALS.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                "Unsupported Express condition: " + normalized
            );
        }
        return new Express(
            normalized,
            List.of(matcher.group(1).split("\\.")),
            matcher.group(2)
        );
    }

    @JsonValue
    public String source() {
        return source;
    }

    public List<String> outputPath() {
        return outputPath;
    }

    public boolean matches(Map<String, ?> outputs) {
        Object actual = outputs;
        for (String segment : outputPath) {
            if (!(actual instanceof Map<?, ?> values)) {
                return false;
            }
            actual = values.get(segment);
        }
        return actual instanceof String && expectedValue.equals(actual);
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
            && Objects.equals(outputPath, other.outputPath)
            && Objects.equals(expectedValue, other.expectedValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, outputPath, expectedValue);
    }

    @Override
    public String toString() {
        return source;
    }
}
