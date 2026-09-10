package org.cses.flow.worker;

import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.Output;
import org.cses.flow.core.plugins.TaskOutputs;

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

    /**
     * 将具体 Output 的业务字段编码为传输映射，状态和错误使用独立信封字段。
     * @param task 非 null 的投递信息，只读取身份
     * @param result 非 null 的具体任务结果，只读
     * @return 新建的结果信封；未声明状态时采用 SUCCESS
     * @throws IllegalArgumentException 状态、错误或业务输出不合法时抛出
     */
    public static WorkerTaskResult from(
        WorkerTask task,
        Output result
    ) {
        return result(
            task,
            result.state().orElse(State.Type.SUCCESS),
            TaskOutputs.values(result),
            result.error().orElse(null)
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
