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
public record WorkerTaskResult(
    String executionId,
    String taskRunId,
    State.Type targetState,
    Map<String, Object> outputs,
    String error
) {

    public static WorkerTaskResult from(
        String executionId,
        String taskRunId,
        State.Type targetState,
        Map<String, ?> outputs,
        String error
    ) {
        return new WorkerTaskResult(
            executionId,
            taskRunId,
            targetState,
            copyOutputs(outputs),
            error
        );
    }

    public WorkerTaskResult {
        targetState = Objects.requireNonNull(
            targetState,
            "targetState"
        );
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
        if (targetState != State.Type.SUCCESS
            && targetState != State.Type.WARNING
            && targetState != State.Type.FAILED
            && targetState != State.Type.KILLED) {
            throw new IllegalArgumentException(
                "Worker target state must be SUCCESS, WARNING, FAILED "
                    + "or KILLED"
            );
        }
        if (targetState == State.Type.FAILED
            && (error == null || error.isBlank())) {
            throw new IllegalArgumentException(
                "Failed Worker result must carry an error"
            );
        }
        if (targetState != State.Type.FAILED && error != null) {
            throw new IllegalArgumentException(
                "Only a failed Worker result may carry an error"
            );
        }
    }

    public static WorkerTaskResult success(
        WorkerTask task,
        Map<String, ?> outputs
    ) {
        return result(
            task,
            State.Type.SUCCESS,
            outputs,
            null
        );
    }

    public static WorkerTaskResult warning(
        WorkerTask task,
        Map<String, ?> outputs
    ) {
        return result(
            task,
            State.Type.WARNING,
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
            State.Type.FAILED,
            Map.of(),
            error
        );
    }

    public static WorkerTaskResult killed(WorkerTask task) {
        return result(
            task,
            State.Type.KILLED,
            Map.of(),
            null
        );
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
        return from(
            task.executionId(),
            task.taskRunId(),
            targetState,
            copied,
            error
        );
    }

    private static Map<String, Object> copyOutputs(
        Map<String, ?> outputs
    ) {
        Map<String, Object> copied = new LinkedHashMap<>();
        if (outputs != null) {
            outputs.forEach(copied::put);
        }
        return copied;
    }
}
