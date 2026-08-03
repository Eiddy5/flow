package org.cses.flow.worker;

import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.RunResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable Worker fact whose target state comes from the shared workflow
 * vocabulary. The Execution aggregate owns the actual State transition and
 * history.
 */
public final class WorkerTaskResult {

    private final String executionId;
    private final String taskRunId;
    private final State.Type targetState;
    private final Map<String, Object> outputs;
    private final String error;

    private WorkerTaskResult(
        String executionId,
        String taskRunId,
        State.Type targetState,
        Map<String, Object> outputs,
        String error
    ) {
        this.executionId = executionId;
        this.taskRunId = taskRunId;
        this.targetState = Objects.requireNonNull(
            targetState,
            "targetState"
        );
        this.outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
        if (targetState != State.Type.COMPLETED
            && targetState != State.Type.TERMINATED) {
            throw new IllegalArgumentException(
                "Worker target state must be COMPLETED or TERMINATED"
            );
        }
        if (targetState == State.Type.TERMINATED
            && (error == null || error.isBlank())) {
            throw new IllegalArgumentException(
                "Terminated Worker result must carry an error"
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
            State.Type.COMPLETED,
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
            State.Type.TERMINATED,
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

    public State.Type targetState() {
        return targetState;
    }

    public Map<String, Object> outputs() {
        return outputs;
    }

    public String error() {
        return error;
    }

    static WorkerTaskResult from(
        WorkerTask task,
        RunResult result
    ) {
        return result(
            task,
            result.targetState(),
            result.outputs(),
            result.error()
        );
    }

    private static WorkerTaskResult result(
        WorkerTask task,
        State.Type targetState,
        Map<String, ?> outputs,
        String error
    ) {
        Map<String, Object> copied = new LinkedHashMap<>();
        if (outputs != null) {
            outputs.forEach(copied::put);
        }
        return new WorkerTaskResult(
            task.executionId(),
            task.taskRunId(),
            targetState,
            copied,
            error
        );
    }
}
