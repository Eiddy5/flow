package org.cses.flow.worker;

import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable Executor-to-Worker envelope for one RunnableTask invocation.
 *
 * <p>The variables map contains additional runtime values plus the reserved
 * {@link RunContext#INPUTS_VARIABLE} entry populated from the TaskRun inputs.</p>
 */
public final class WorkerTask {

    private final String executionId;
    private final String taskRunId;
    private final RunnableTask runnableTask;
    private final Map<String, Object> variables;

    public WorkerTask(
        String executionId,
        String taskRunId,
        Task task,
        Map<String, Object> inputs
    ) {
        this(executionId, taskRunId, task, inputs, Map.of());
    }

    public WorkerTask(
        String executionId,
        String taskRunId,
        Task task,
        Map<String, ?> inputs,
        Map<String, ?> variables
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
        Map<String, Object> runtimeVariables = new LinkedHashMap<>();
        if (variables != null) {
            variables.forEach((key, value) -> {
                if (RunContext.INPUTS_VARIABLE.equals(key)) {
                    throw new IllegalArgumentException(
                        "Worker variables must not contain reserved input "
                            + "variable: " + RunContext.INPUTS_VARIABLE
                    );
                }
                runtimeVariables.put(
                    Objects.requireNonNull(key, "Worker variable key"),
                    Objects.requireNonNull(
                        value,
                        () -> "Worker variable must not be null: " + key
                    )
                );
            });
        }
        runtimeVariables.put(
            RunContext.INPUTS_VARIABLE,
            inputs == null ? Map.of() : Map.copyOf(inputs)
        );
        this.variables = Map.copyOf(runtimeVariables);
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

    public Map<String, Object> variables() {
        return variables;
    }

    public Map<String, Object> inputs() {
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = (Map<String, Object>) variables.get(
            RunContext.INPUTS_VARIABLE
        );
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
