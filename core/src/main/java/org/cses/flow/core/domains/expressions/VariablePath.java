package org.cses.flow.core.domains.expressions;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, safe dot-separated path into runtime variables.
 *
 * <p>The path only traverses maps. It never invokes methods, reads Java
 * members, evaluates scripts, or coerces a missing value.</p>
 */
public class VariablePath {

    private List<String> segments;

    private VariablePath(List<String> segments) {
        this.segments = List.copyOf(segments);
    }

    public static VariablePath parse(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException(
                "Variable path must not be blank"
            );
        }
        return from(List.of(source.trim().split("\\.", -1)));
    }

    public static VariablePath from(List<String> segments) {
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException(
                "Variable path must not be empty"
            );
        }
        for (String segment : segments) {
            if (!isSafeSegment(segment)) {
                throw new IllegalArgumentException(
                    "Variable path contains an invalid segment: " + segment
                );
            }
        }
        return new VariablePath(segments);
    }

    public List<String> segments() {
        return segments;
    }

    public String source() {
        return String.join(".", segments);
    }

    public Optional<Object> resolve(Map<String, ?> variables) {
        Object current = variables == null ? Map.of() : variables;
        for (String segment : segments) {
            if (!(current instanceof Map<?, ?> values)
                || !values.containsKey(segment)) {
                return Optional.empty();
            }
            current = values.get(segment);
            if (current == null) {
                return Optional.empty();
            }
        }
        return Optional.of(current);
    }

    @Override
    public boolean equals(Object value) {
        return this == value
            || value instanceof VariablePath other
            && segments.equals(other.segments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(segments);
    }

    @Override
    public String toString() {
        return source();
    }

    private static boolean isSafeSegment(String segment) {
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
}
