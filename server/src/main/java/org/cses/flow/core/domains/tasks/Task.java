package org.cses.flow.core.domains.tasks;

import org.cses.flow.core.domains.flows.Data;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Immutable base domain object for a Task definition.
 */
public abstract class Task {

    private final String id;
    private final String parentId;
    private final String key;
    private final String type;
    private final List<Input> inputs;
    private final List<Output> outputs;
    private final RouteExpression route;
    private final List<String> dependOn;
    private final List<Task> tasks;

    protected Task(
        String id,
        String parentId,
        String key,
        String type,
        List<? extends Input> inputs,
        List<? extends Output> outputs,
        RouteExpression route,
        List<String> dependOn,
        List<? extends Task> tasks
    ) {
        this.id = requireText(id, "Task id");
        this.parentId = normalizeOptionalText(parentId);
        this.key = requireText(key, "Task key");
        this.type = requireText(type, "Task type");
        this.inputs = immutableData(inputs, "Task inputs");
        this.outputs = immutableData(outputs, "Task outputs");
        this.route = Objects.requireNonNull(route, "Task route");
        this.dependOn = immutableTextList(dependOn, "Task dependOn");
        this.tasks = tasks == null ? List.of() : List.copyOf(tasks);
        for (Task task : this.tasks) {
            if (!task.parentId().filter(this.id::equals).isPresent()) {
                throw new IllegalArgumentException(
                    "Child Task parent id must equal " + this.id
                );
            }
        }
    }

    public final String id() {
        return id;
    }

    public final Optional<String> parentId() {
        return Optional.ofNullable(parentId);
    }

    public final String key() {
        return key;
    }

    public final String type() {
        return type;
    }

    public final List<Input> inputs() {
        return inputs;
    }

    public final List<Output> outputs() {
        return outputs;
    }

    public final RouteExpression route() {
        return route;
    }

    public final List<String> dependOn() {
        return dependOn;
    }

    public final List<Task> tasks() {
        return tasks;
    }

    public final boolean isTopLevel() {
        return parentId == null;
    }

    public final boolean matchesRoute(Map<String, ?> parentOutputs) {
        return route.matches(parentOutputs);
    }

    public final boolean declaresOutput(String outputKey) {
        return outputs.stream().anyMatch(output ->
            output.getKey().equals(outputKey)
        );
    }

    public final boolean dependsOn(String taskKey) {
        return dependOn.contains(taskKey);
    }

    public final Optional<Task> findDescendant(String taskId) {
        return tasks.stream()
            .flatMap(task -> Stream.concat(
                Stream.of(task),
                task.allDescendants().stream()
            ))
            .filter(task -> task.id().equals(taskId))
            .findFirst();
    }

    public final List<Task> allDescendants() {
        return tasks.stream()
            .flatMap(task -> Stream.concat(
                Stream.of(task),
                task.allDescendants().stream()
            ))
            .toList();
    }

    /**
     * Concrete subtypes override this when they own explicit extra fields.
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
            && Objects.equals(parentId, other.parentId)
            && Objects.equals(key, other.key)
            && Objects.equals(type, other.type)
            && Objects.equals(inputs, other.inputs)
            && Objects.equals(outputs, other.outputs)
            && Objects.equals(route, other.route)
            && Objects.equals(dependOn, other.dependOn)
            && Objects.equals(tasks, other.tasks)
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
            parentId,
            key,
            type,
            inputs,
            outputs,
            route,
            dependOn,
            tasks,
            typeSpecificEqualityState()
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static <T extends Data> List<T> immutableData(
        List<? extends T> source,
        String field
    ) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<T> copy = new ArrayList<>(source.size());
        HashSet<String> keys = new HashSet<>();
        for (T data : source) {
            if (data == null) {
                throw new IllegalArgumentException(
                    field + " must not contain null values"
                );
            }
            if (!keys.add(data.getKey())) {
                throw new IllegalArgumentException(
                    field + " contains duplicate key: " + data.getKey()
                );
            }
            copy.add(data);
        }
        return List.copyOf(copy);
    }

    private static List<String> immutableTextList(
        List<String> values,
        String field
    ) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(values.size());
        HashSet<String> unique = new HashSet<>();
        for (String value : values) {
            String text = requireText(value, field + " value");
            if (!unique.add(text)) {
                throw new IllegalArgumentException(
                    field + " contains duplicate key: " + text
                );
            }
            result.add(text);
        }
        return List.copyOf(result);
    }
}
