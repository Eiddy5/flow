package org.cses.flow.core.domains.tasks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.cses.flow.core.exceptions.WorkflowException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable string template that reads scalar values from Task run inputs.
 */
public final class TemplateExpression {

    private static final Pattern PATH = Pattern.compile(
        "[A-Za-z][A-Za-z0-9_-]*(?:\\.[A-Za-z][A-Za-z0-9_-]*)*"
    );

    private final String source;
    private final List<Segment> segments;

    private TemplateExpression(String source, List<Segment> segments) {
        this.source = source;
        this.segments = List.copyOf(segments);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static TemplateExpression parse(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException(
                "Task template expression must not be blank"
            );
        }
        return new TemplateExpression(source, parseSegments(source));
    }

    @JsonValue
    public String source() {
        return source;
    }

    public String render(Map<String, ?> values) {
        Map<String, ?> variables = values == null ? Map.of() : values;
        StringBuilder rendered = new StringBuilder(source.length());
        for (Segment segment : segments) {
            if (segment.path == null) {
                rendered.append(segment.value);
            } else {
                rendered.append(resolveScalar(variables, segment.path));
            }
        }
        return rendered.toString();
    }

    private static List<Segment> parseSegments(String source) {
        List<Segment> segments = new ArrayList<>();
        int cursor = 0;
        while (cursor < source.length()) {
            int open = source.indexOf("{{", cursor);
            int unmatchedClose = source.indexOf("}}", cursor);
            if (unmatchedClose >= 0
                && (open < 0 || unmatchedClose < open)) {
                throw invalidSyntax(source);
            }
            if (open < 0) {
                segments.add(Segment.literal(source.substring(cursor)));
                break;
            }
            if (open > cursor) {
                segments.add(Segment.literal(source.substring(cursor, open)));
            }
            int close = source.indexOf("}}", open + 2);
            if (close < 0) {
                throw invalidSyntax(source);
            }
            String path = source.substring(open + 2, close).trim();
            if (!PATH.matcher(path).matches()) {
                throw invalidSyntax(source);
            }
            segments.add(Segment.path(path));
            cursor = close + 2;
        }
        if (segments.isEmpty()) {
            segments.add(Segment.literal(source));
        }
        return segments;
    }

    private static IllegalArgumentException invalidSyntax(String source) {
        return new IllegalArgumentException(
            "Invalid Task template expression: " + source
        );
    }

    private static String resolveScalar(
        Map<String, ?> variables,
        String path
    ) {
        Object current = variables;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)
                || !map.containsKey(segment)) {
                throw missingPath(path);
            }
            current = map.get(segment);
        }
        if (current == null) {
            throw missingPath(path);
        }
        if (current instanceof CharSequence
            || current instanceof Number
            || current instanceof Boolean
            || current instanceof Character
            || current instanceof Enum<?>) {
            return current.toString();
        }
        throw new WorkflowException(
            "Task template expression path is not a scalar value: " + path
        );
    }

    private static WorkflowException missingPath(String path) {
        return new WorkflowException(
            "Task template expression path is missing: " + path
        );
    }

    @Override
    public boolean equals(Object value) {
        return this == value
            || value instanceof TemplateExpression other
            && source.equals(other.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source);
    }

    @Override
    public String toString() {
        return source;
    }

    private static final class Segment {

        private final String value;
        private final String path;

        private Segment(String value, String path) {
            this.value = value;
            this.path = path;
        }

        private static Segment literal(String value) {
            return new Segment(value, null);
        }

        private static Segment path(String path) {
            return new Segment(null, path);
        }
    }
}
