package org.cses.flow.worker;

import org.cses.flow.core.domains.tasks.Task;

import java.util.Map;

public final class WorkerTask {

    private final String executionId;
    private final String taskRunId;
    private final Task task;
    private final Map<String, Object> inputs;

    public WorkerTask(
        String executionId,
        String taskRunId,
        Task task,
        Map<String, Object> inputs
    ) {
        this.executionId = requireText(executionId, "Execution id");
        this.taskRunId = requireText(taskRunId, "TaskRun id");
        this.task = java.util.Objects.requireNonNull(task, "task");
        this.inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    }

    public String executionId() {
        return executionId;
    }

    public String taskRunId() {
        return taskRunId;
    }

    public Task task() {
        return task;
    }

    public Map<String, Object> inputs() {
        return inputs;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                field + " must not be blank"
            );
        }
        return value.trim();
    }
}
