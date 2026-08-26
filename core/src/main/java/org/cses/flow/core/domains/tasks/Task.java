package org.cses.flow.core.domains.tasks;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.exceptions.WorkflowException;

import java.util.*;
import java.util.stream.Stream;

/**
 * Base domain object for a bound Task plugin definition.
 *
 * <p>Jackson and Micronaut may populate fields through the public no-args
 * constructor. No mutation methods are exposed after binding.</p>
 */
@SuperBuilder
@NoArgsConstructor
public abstract class Task implements TaskInterface {

    @NotBlank
    String id;

    @NotBlank
    String key;

    String displayName;

    @NotNull
    @Builder.Default
    List<Input<?>> inputs = List.of();

    @NotNull
    @Builder.Default
    List<Output> outputs = List.of();

    @NotNull
    @Builder.Default
    TaskRoute route = TaskRoute.direct();

    @NotNull
    @Builder.Default
    List<@NotBlank String> dependOn = List.of();

    @NotNull
    @Builder.Default
    List<Task> tasks = List.of();

    public final String id() {
        return id;
    }

    public final boolean identifiedBy(String taskId) {
        return Objects.equals(id, taskId);
    }

    public final void reidentify(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("Task id must not be blank");
        }
        id = taskId;
    }

    public final String key() {
        return key;
    }

    public final String displayName() {
        return displayName == null || displayName.isBlank()
                ? key()
                : displayName.trim();
    }

    public final List<Input<?>> inputs() {
        return inputs == null ? List.of() : List.copyOf(inputs);
    }

    public List<Output> outputs() {
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

    public final boolean matchesRoute(
            Map<String, ?> parentOutputs,
            Map<String, ?> flowInputs
    ) {
        return route.matches(parentOutputs, flowInputs);
    }

    public final boolean matchesRoute(
            Map<String, ?> parentOutputs,
            Map<String, ?> flowInputs,
            Map<String, ?> flowVariables
    ) {
        return route.matches(parentOutputs, flowInputs, flowVariables);
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
                .filter(task -> task.id.equals(taskId))
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
                && Objects.equals(displayName(), other.displayName())
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
                displayName(),
                inputs(),
                outputs(),
                route,
                dependOn(),
                tasks(),
                typeSpecificEqualityState()
        );
    }
}
