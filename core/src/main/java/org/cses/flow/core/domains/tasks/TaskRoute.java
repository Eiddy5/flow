package org.cses.flow.core.domains.tasks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.expressions.Express;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A Task route that is either DIRECT or guarded by one safe condition.
 */
public final class TaskRoute {

    private static final String DIRECT = "DIRECT";

    private final String source;
    private final Express condition;

    private TaskRoute(String source, Express condition) {
        this.source = source;
        this.condition = condition;
    }

    public static TaskRoute direct() {
        return new TaskRoute(DIRECT, null);
    }

    @JsonCreator
    public static TaskRoute parse(String source) {
        String normalized = source == null || source.isBlank()
            ? DIRECT
            : source.trim();
        if (DIRECT.equals(normalized)) {
            return direct();
        }
        Express condition;
        try {
            condition = Express.parse(normalized);
        } catch (IllegalArgumentException exception) {
            throw unsupported(normalized, exception);
        }
        if (condition.outputPath().size() != 1) {
            throw unsupported(normalized, null);
        }
        return new TaskRoute(condition.source(), condition);
    }

    @JsonValue
    public String source() {
        return source;
    }

    public Optional<String> referencedOutputKey() {
        return condition == null || !condition.referencesOutputs()
            ? Optional.empty()
            : Optional.of(condition.referencedKey());
    }

    public Optional<String> referencedInputKey() {
        return condition == null || !condition.referencesInputs()
            ? Optional.empty()
            : Optional.of(condition.referencedKey());
    }

    public Optional<String> referencedVariableKey() {
        return condition == null || !condition.referencesVariables()
            ? Optional.empty()
            : Optional.of(condition.referencedKey());
    }

    public boolean supportsOrderedComparison() {
        return condition != null && condition.supportsOrderedComparison();
    }

    public boolean supports(DataType type) {
        return condition == null || condition.supports(type);
    }

    public Optional<Express> condition() {
        return Optional.ofNullable(condition);
    }

    public boolean matches(Map<String, ?> parentOutputs) {
        return condition == null || condition.matches(parentOutputs);
    }

    public boolean matches(
        Map<String, ?> parentOutputs,
        Map<String, ?> flowInputs
    ) {
        return matches(parentOutputs, flowInputs, Map.of());
    }

    public boolean matches(
        Map<String, ?> parentOutputs,
        Map<String, ?> flowInputs,
        Map<String, ?> flowVariables
    ) {
        return condition == null
            || condition.matches(parentOutputs, flowInputs, flowVariables);
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof TaskRoute other)) {
            return false;
        }
        return Objects.equals(source, other.source)
            && Objects.equals(condition, other.condition);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, condition);
    }

    @Override
    public String toString() {
        return source;
    }

    private static IllegalArgumentException unsupported(
        String source,
        IllegalArgumentException cause
    ) {
        String message = "Unsupported Task route expression: " + source;
        return cause == null
            ? new IllegalArgumentException(message)
            : new IllegalArgumentException(message, cause);
    }
}
