package org.cses.flow.worker;

import java.util.Map;

public final class WorkerTaskResult {

    private final String executionId;
    private final String taskRunId;
    private final WorkerTaskOutcome outcome;
    private final Map<String, Object> outputs;
    private final String error;

    private WorkerTaskResult(
        String executionId,
        String taskRunId,
        WorkerTaskOutcome outcome,
        Map<String, Object> outputs,
        String error
    ) {
        this.executionId = executionId;
        this.taskRunId = taskRunId;
        this.outcome = java.util.Objects.requireNonNull(
            outcome,
            "outcome"
        );
        this.outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
        if (outcome == WorkerTaskOutcome.FAILED
            && (error == null || error.isBlank())) {
            throw new IllegalArgumentException(
                "Failed Worker result must carry an error"
            );
        }
        this.error = error;
    }

    public static WorkerTaskResult completed(
        WorkerTask task,
        Map<String, ?> outputs
    ) {
        return result(
            task,
            WorkerTaskOutcome.COMPLETED,
            outputs,
            null
        );
    }

    public static WorkerTaskResult running(
        WorkerTask task,
        Map<String, ?> outputs
    ) {
        return result(
            task,
            WorkerTaskOutcome.RUNNING,
            outputs,
            null
        );
    }

    public static WorkerTaskResult failed(
        WorkerTask task,
        String error
    ) {
        return result(
            task,
            WorkerTaskOutcome.FAILED,
            Map.of(),
            error
        );
    }

    public String executionId() {
        return executionId;
    }

    public String taskRunId() {
        return taskRunId;
    }

    public WorkerTaskOutcome outcome() {
        return outcome;
    }

    public Map<String, Object> outputs() {
        return outputs;
    }

    public String error() {
        return error;
    }

    private static WorkerTaskResult result(
        WorkerTask task,
        WorkerTaskOutcome outcome,
        Map<String, ?> outputs,
        String error
    ) {
        Map<String, Object> copied = new java.util.LinkedHashMap<>();
        if (outputs != null) {
            outputs.forEach(copied::put);
        }
        return new WorkerTaskResult(
            task.executionId(),
            task.taskRunId(),
            outcome,
            copied,
            error
        );
    }
}
