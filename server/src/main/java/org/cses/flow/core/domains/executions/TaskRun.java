package org.cses.flow.core.domains.executions;

import org.cses.flow.core.exceptions.shared.WorkflowException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One real execution occurrence of a Task definition.
 */
public final class TaskRun {

    private final String id;
    private final String taskId;
    private final String parentId;
    private final Map<String, Object> inputs;
    private TaskRunStatus status;
    private Map<String, Object> outputs;
    private String error;

    TaskRun(
        String id,
        String taskId,
        String parentId,
        Map<String, ?> inputs
    ) {
        this.id = requireText(id, "TaskRun id");
        this.taskId = requireText(taskId, "Task id");
        this.parentId = normalizeOptionalText(parentId);
        this.inputs = immutableMap(inputs);
        this.status = TaskRunStatus.CREATED;
        this.outputs = Map.of();
    }

    private TaskRun(
        String id,
        String taskId,
        String parentId,
        Map<String, ?> inputs,
        TaskRunStatus status,
        Map<String, ?> outputs,
        String error
    ) {
        this.id = requireText(id, "TaskRun id");
        this.taskId = requireText(taskId, "Task id");
        this.parentId = normalizeOptionalText(parentId);
        this.inputs = immutableMap(inputs);
        this.status = Objects.requireNonNull(status, "TaskRun status");
        this.outputs = immutableMap(outputs);
        this.error = normalizeOptionalText(error);
        if (status == TaskRunStatus.FAILED && this.error == null) {
            throw new IllegalArgumentException(
                "Failed TaskRun must have an error"
            );
        }
    }

    /**
     * Rehydrates a TaskRun from a trusted persistence adapter.
     */
    public static TaskRun rehydrate(
        String id,
        String taskId,
        String parentId,
        Map<String, ?> inputs,
        TaskRunStatus status,
        Map<String, ?> outputs,
        String error
    ) {
        return new TaskRun(
            id,
            taskId,
            parentId,
            inputs,
            status,
            outputs,
            error
        );
    }

    private TaskRun(TaskRun source) {
        this.id = source.id;
        // The memory adapter uses copies as persistence round-trips. Recreate
        // the reference so orchestration tests cannot accidentally pass by
        // relying on String object identity.
        this.taskId = new String(source.taskId);
        this.parentId = source.parentId;
        this.inputs = source.inputs;
        this.status = source.status;
        this.outputs = source.outputs;
        this.error = source.error;
    }

    public String id() {
        return id;
    }

    public String taskId() {
        return taskId;
    }

    public Optional<String> parentId() {
        return Optional.ofNullable(parentId);
    }

    public Map<String, Object> inputs() {
        return inputs;
    }

    public TaskRunStatus status() {
        return status;
    }

    public Map<String, Object> outputs() {
        return outputs;
    }

    public Optional<String> error() {
        return Optional.ofNullable(error);
    }

    public boolean isActive() {
        return status == TaskRunStatus.CREATED
            || status == TaskRunStatus.RUNNING;
    }

    void start() {
        requireStatus(TaskRunStatus.CREATED);
        status = TaskRunStatus.RUNNING;
    }

    void complete(Map<String, ?> completedOutputs) {
        requireStatus(TaskRunStatus.RUNNING);
        outputs = immutableMap(completedOutputs);
        error = null;
        status = TaskRunStatus.COMPLETED;
    }

    void fail(String failure) {
        requireStatus(TaskRunStatus.RUNNING);
        if (failure == null || failure.isBlank()) {
            throw new IllegalArgumentException("TaskRun error must not be blank");
        }
        error = failure.trim();
        status = TaskRunStatus.FAILED;
    }

    void cancel() {
        if (status != TaskRunStatus.CREATED
            && status != TaskRunStatus.RUNNING) {
            throw new WorkflowException(
                "TaskRun cannot be canceled from " + status + ": " + id
            );
        }
        status = TaskRunStatus.CANCELED;
    }

    TaskRun copy() {
        return new TaskRun(this);
    }

    private void requireStatus(TaskRunStatus expected) {
        if (status != expected) {
            throw new WorkflowException(
                "TaskRun " + id + " must be " + expected + " but was " + status
            );
        }
    }

    private static Map<String, Object> immutableMap(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(
            key,
            immutableValue(value)
        ));
        return Collections.unmodifiableMap(copy);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                field + " must not be blank"
            );
        }
        return value.trim();
    }

    private static String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(
                String.valueOf(key),
                immutableValue(nested)
            ));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(TaskRun::immutableValue).toList();
        }
        return value;
    }
}
