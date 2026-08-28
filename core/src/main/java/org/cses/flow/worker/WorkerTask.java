package org.cses.flow.worker;

import org.cses.flow.core.domains.expressions.VariablePath;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable Executor-to-Worker envelope for one RunnableTask invocation.
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
        Map<String, ?> variables
    ) {
        return from(
            executionId,
            taskRunId,
            null,
            task,
            variables
        );
    }

    public static WorkerTask from(
        String executionId,
        String taskRunId,
        String parentTaskRunId,
        Task task,
        Map<String, ?> variables
    ) {
        return new WorkerTask(
            executionId,
            taskRunId,
            optionalText(parentTaskRunId),
            requireRunnableTask(task),
            immutableVariables(variables)
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
        variables = immutableVariables(variables);
        requireIdentity(variables, "execution.id", executionId);
        requireIdentity(variables, "taskRun.id", taskRunId);
        validateParentIdentity(parentTaskRunId, variables);
    }

    public Map<String, Object> taskInputs() {
        Object value = VariablePath.parse("taskRun.inputs")
            .resolve(variables)
            .orElse(Map.of());
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException(
                "Worker variables taskRun.inputs must contain a Map"
            );
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = (Map<String, Object>) map;
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
        Map<String, ?> source
    ) {
        Objects.requireNonNull(source, "Worker variables");
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(
            Objects.requireNonNull(key, "Worker variable key"),
            immutableValue(value)
        ));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> copy = new LinkedHashMap<>();
            nested.forEach((key, nestedValue) -> copy.put(
                String.valueOf(key),
                immutableValue(nestedValue)
            ));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return list.stream()
                .map(WorkerTask::immutableValue)
                .toList();
        }
        return value;
    }

    private static void requireIdentity(
        Map<String, Object> variables,
        String path,
        String expected
    ) {
        Object value = VariablePath.parse(path).resolve(variables)
            .orElseThrow(() -> new IllegalArgumentException(
                "Worker variables must contain " + path
            ));
        if (!expected.equals(value)) {
            throw new IllegalArgumentException(
                "Worker identity does not match variables: "
                    + value + " != " + expected
            );
        }
    }

    private static void validateParentIdentity(
        Optional<String> expected,
        Map<String, Object> variables
    ) {
        Optional<String> actual = VariablePath.parse("parent.taskRun.id")
            .resolve(variables)
            .map(String::valueOf);
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                "Worker parent identity does not match variables"
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
