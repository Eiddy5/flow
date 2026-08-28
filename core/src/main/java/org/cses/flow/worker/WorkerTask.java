package org.cses.flow.worker;

import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable Executor-to-Worker envelope for one RunnableTask invocation.
 *
 * <p>The variables map contains additional runtime values plus the reserved
 * {@link RunContext#INPUTS_VARIABLE} entry populated from the Execution and
 * {@link RunContext#TASK_INPUTS_VARIABLE} populated from the TaskRun.
 * {@link WorkerDispatcher} injects the envelope's exact taskRunId and optional
 * parent TaskRun id into invocation-only {@link RunContext} entries.</p>
 */
public record WorkerTask(
    String executionId,
    String taskRunId,
    Optional<String> parentTaskRunId,
    RunnableTask runnableTask,
    Map<String, Object> variables
) {

    public static WorkerTask from(
        String executionId,
        String taskRunId,
        Task task,
        Map<String, ?> inputs
    ) {
        return new WorkerTask(
            executionId,
            taskRunId,
            null,
            task,
            inputs,
            Map.of()
        );
    }

    public static WorkerTask from(
        String executionId,
        String taskRunId,
        Task task,
        Map<String, ?> inputs,
        Map<String, ?> variables
    ) {
        return new WorkerTask(
            executionId,
            taskRunId,
            task,
            inputs,
            variables
        );
    }

    public static WorkerTask from(
        String executionId,
        String taskRunId,
        String parentTaskRunId,
        Task task,
        Map<String, ?> inputs,
        Map<String, ?> variables
    ) {
        return new WorkerTask(
            executionId,
            taskRunId,
            parentTaskRunId,
            task,
            inputs,
            variables
        );
    }

    public static WorkerTask from(
        String executionId,
        String taskRunId,
        Optional<String> parentTaskRunId,
        RunnableTask runnableTask,
        Map<String, Object> variables
    ) {
        return new WorkerTask(
            executionId,
            taskRunId,
            parentTaskRunId,
            runnableTask,
            variables
        );
    }

    public WorkerTask(
        String executionId,
        String taskRunId,
        Task task,
        Map<String, Object> inputs
    ) {
        this(executionId, taskRunId, null, task, inputs, Map.of());
    }

    public WorkerTask(
        String executionId,
        String taskRunId,
        Task task,
        Map<String, ?> inputs,
        Map<String, ?> variables
    ) {
        this(executionId, taskRunId, null, task, inputs, variables);
    }

    public WorkerTask(
        String executionId,
        String taskRunId,
        String parentTaskRunId,
        Task task,
        Map<String, ?> inputs,
        Map<String, ?> variables
    ) {
        this(
            executionId,
            taskRunId,
            optionalText(parentTaskRunId),
            requireRunnableTask(task),
            immutableVariables(inputs, variables)
        );
    }

    public WorkerTask {
        executionId = requireText(executionId, "Execution id");
        taskRunId = requireText(taskRunId, "TaskRun id");
        parentTaskRunId = Objects.requireNonNull(
            parentTaskRunId,
            "Parent TaskRun id"
        );
        runnableTask = Objects.requireNonNull(
            runnableTask,
            "Runnable task"
        );
        variables = Map.copyOf(Objects.requireNonNull(
            variables,
            "Worker variables"
        ));
        validateExecutionVariable(executionId, variables);
    }

    public Map<String, Object> taskInputs() {
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = (Map<String, Object>) variables.get(
            RunContext.TASK_INPUTS_VARIABLE
        );
        return inputs;
    }

    private static RunnableTask requireRunnableTask(Task task) {
        Task taskDefinition = Objects.requireNonNull(task, "task");
        if (!(taskDefinition instanceof RunnableTask capability)) {
            throw new IllegalArgumentException(
                "WorkerTask requires a RunnableTask: "
                    + taskDefinition.getType()
            );
        }
        return capability;
    }

    private static Map<String, Object> immutableVariables(
        Map<String, ?> inputs,
        Map<String, ?> variables
    ) {
        Map<String, Object> runtimeVariables = new LinkedHashMap<>();
        if (variables != null) {
            variables.forEach((key, value) -> {
                if (RunContext.INPUTS_VARIABLE.equals(key)
                    || RunContext.TASK_INPUTS_VARIABLE.equals(key)
                    || RunContext.TASK_RUN_ID_VARIABLE.equals(key)
                    || RunContext.PARENT_TASK_RUN_ID_VARIABLE.equals(key)) {
                    throw new IllegalArgumentException(
                        "Worker variables must not contain reserved variable: "
                            + key
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
        Object executionValue = runtimeVariables.get(
            RunContext.EXECUTION_VARIABLE
        );
        Map<String, Object> executionInputs = executionValue instanceof Execution
            ? ((Execution) executionValue).inputs()
            : Map.of();
        runtimeVariables.put(RunContext.INPUTS_VARIABLE, executionInputs);
        runtimeVariables.put(
            RunContext.TASK_INPUTS_VARIABLE,
            inputs == null ? Map.of() : Map.copyOf(inputs)
        );
        return Map.copyOf(runtimeVariables);
    }

    private static void validateExecutionVariable(
        String executionId,
        Map<String, Object> variables
    ) {
        Object value = variables.get(RunContext.EXECUTION_VARIABLE);
        if (value == null) {
            return;
        }
        if (!(value instanceof Execution execution)) {
            throw new IllegalArgumentException(
                "Worker variable " + RunContext.EXECUTION_VARIABLE
                    + " must contain an Execution"
            );
        }
        if (!executionId.equals(execution.id())) {
            throw new IllegalArgumentException(
                "Worker Execution id does not match envelope: "
                    + execution.id() + " != " + executionId
            );
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                field + " must not be blank"
            );
        }
        return value.trim();
    }

    private static Optional<String> optionalText(String value) {
        return value == null
            ? Optional.empty()
            : Optional.of(requireText(value, "Parent TaskRun id"));
    }
}
