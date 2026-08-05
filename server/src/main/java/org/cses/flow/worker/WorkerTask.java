package org.cses.flow.worker;

import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable Executor-to-Worker envelope for one RunnableTask invocation.
 */
public final class WorkerTask {

    private final String executionId;
    private final String taskRunId;
    private final RunnableTask runnableTask;
    private final Map<String, Object> inputs;

    public WorkerTask(
        String executionId,
        String taskRunId,
        Task task,
        Map<String, Object> inputs
    ) {
        this.executionId = requireText(executionId, "Execution id");
        this.taskRunId = requireText(taskRunId, "TaskRun id");
        Task taskDefinition = Objects.requireNonNull(task, "task");
        if (!(taskDefinition instanceof RunnableTask capability)) {
            throw new IllegalArgumentException(
                "WorkerTask requires a RunnableTask: "
                    + taskDefinition.getType()
            );
        }
        this.runnableTask = capability;
        this.inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    }

    public String executionId() {
        return executionId;
    }

    public String taskRunId() {
        return taskRunId;
    }

    public RunnableTask runnableTask() {
        return runnableTask;
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
