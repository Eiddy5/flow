package org.cses.flow.core.domains.tasks;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.Identified;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.plugins.Plugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Base domain object for a bound Task plugin definition.
 *
 * <p>Jackson and Micronaut may populate fields through the public no-args
 * constructor. No mutation methods are exposed after binding.</p>
 */
@SuperBuilder
@NoArgsConstructor
public abstract class Task implements Plugin, Identified {

    @NotBlank
    private String id;

    @NotBlank
    private String key;

    @NotNull
    @Builder.Default
    private List<Input<?>> inputs = List.of();

    @NotNull
    @Builder.Default
    private List<Output> outputs = List.of();

    @NotNull
    @Builder.Default
    private TaskRoute route = TaskRoute.direct();

    @NotNull
    @Builder.Default
    private List<@NotBlank String> dependOn = List.of();

    @NotNull
    @Builder.Default
    private List<Task> tasks = List.of();

    public final String id() {
        return id;
    }

    @Override
    public final String identifier() {
        return id;
    }

    public final String key() {
        return key;
    }

    public final List<Input<?>> inputs() {
        return inputs == null ? List.of() : List.copyOf(inputs);
    }

    public List<Output> outputs() {
        return outputs == null ? List.of() : List.copyOf(outputs);
    }

    /**
     * Returns the outputs bound directly to the common Task field.
     *
     * <p>Concrete Tasks whose effective outputs are derived from a
     * type-specific definition can use this value when validating a restored
     * definition without exposing a second public output contract.</p>
     */
    protected final List<Output> configuredOutputs() {
        return outputs == null ? List.of() : List.copyOf(outputs);
    }

    public final TaskRoute route() {
        return route;
    }

    public final List<String> dependOn() {
        return dependOn == null ? List.of() : List.copyOf(dependOn);
    }

    public final List<Task> tasks() {
        return tasks == null ? List.of() : List.copyOf(tasks);
    }

    /**
     * Returns every directly-contained Task definition owned by this Task.
     *
     * <p>The common implementation is the ordinary post-completion
     * {@link #tasks()} list. A concrete orchestration Task may expose a
     * type-specific containment relation without changing that list's
     * scheduling meaning.</p>
     */
    public List<Task> definitionChildren() {
        return tasks();
    }

    public final boolean matchesRoute(Map<String, ?> parentOutputs) {
        return route.matches(parentOutputs);
    }

    public final boolean declaresOutput(String outputKey) {
        return outputs().stream().anyMatch(output ->
            output.getKey().equals(outputKey)
        );
    }

    public final boolean declaresInput(String inputKey) {
        return inputs().stream().anyMatch(input ->
            input.getKey().equals(inputKey)
        );
    }

    /**
     * Validates runtime outputs against this bound Task definition.
     */
    public final Map<String, Object> validateOutputs(
        Map<String, ?> actualOutputs
    ) {
        if (actualOutputs == null) {
            throw new WorkflowException("Task outputs must be provided");
        }
        LinkedHashSet<String> declared = outputs().stream()
            .map(Output::getKey)
            .collect(java.util.stream.Collectors.toCollection(
                LinkedHashSet::new
            ));
        LinkedHashSet<String> unsupported = new LinkedHashSet<>(
            actualOutputs.keySet()
        );
        unsupported.removeAll(declared);
        if (!unsupported.isEmpty()) {
            throw new WorkflowException(
                "Task outputs were not declared: " + unsupported
            );
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : actualOutputs.entrySet()) {
            Output output = outputs().stream()
                .filter(candidate -> candidate.getKey().equals(entry.getKey()))
                .findFirst()
                .orElseThrow();
            try {
                normalized.put(
                    entry.getKey(),
                    output.normalized(entry.getValue())
                );
            } catch (IllegalArgumentException exception) {
                throw new WorkflowException(exception.getMessage());
            }
        }
        return Collections.unmodifiableMap(normalized);
    }

    public final boolean dependsOn(String taskKey) {
        return dependOn().contains(taskKey);
    }

    public final Optional<Task> findDescendant(String taskId) {
        return definitionChildren().stream()
            .flatMap(task -> Stream.concat(
                Stream.of(task),
                task.allDescendants().stream()
            ))
            .filter(task -> task.identifiedBy(taskId))
            .findFirst();
    }

    public final List<Task> allDescendants() {
        return definitionChildren().stream()
            .flatMap(task -> Stream.concat(
                Stream.of(task),
                task.allDescendants().stream()
            ))
            .toList();
    }

    /**
     * Concrete subtypes can expose additional equality state when needed.
     */
    protected Object typeSpecificEqualityState() {
        return null;
    }

    @Override
    public final boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof Task other)) {
            return false;
        }
        return getClass().equals(other.getClass())
            && Objects.equals(id, other.id)
            && Objects.equals(key, other.key)
            && Objects.equals(inputs(), other.inputs())
            && Objects.equals(outputs(), other.outputs())
            && Objects.equals(route, other.route)
            && Objects.equals(dependOn(), other.dependOn())
            && Objects.equals(tasks(), other.tasks())
            && Objects.equals(
                typeSpecificEqualityState(),
                other.typeSpecificEqualityState()
            );
    }

    @Override
    public final int hashCode() {
        return Objects.hash(
            getClass(),
            id,
            key,
            inputs(),
            outputs(),
            route,
            dependOn(),
            tasks(),
            typeSpecificEqualityState()
        );
    }
}
