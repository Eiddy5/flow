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
    int attemptNumber,
    State.Type targetState,
    Map<String, Object> outputs,
    String error
) {

    /**
     * 创建关联到精确尝试的 Worker 结果。
     * @param executionId 所属实例身份
     * @param taskRunId 所属任务运行身份
     * @param attemptNumber 从 1 开始的实际调用编号
     * @param targetState Worker 终态
     * @param outputs 可空业务结果，会复制
     * @param error 仅失败时必填
     * @return 不可变结果信封
     */
    public static WorkerTaskResult from(
        String executionId,
        String taskRunId,
        int attemptNumber,
        State.Type targetState,
        Map<String, ?> outputs,
        String error
    ) {
        return new WorkerTaskResult(
            executionId,
            taskRunId,
            attemptNumber,
            targetState,
            copyOutputs(outputs),
            error
        );
    }

    /**
     * 校验实际调用编号和结果状态。
     * @param executionId 实例身份
     * @param taskRunId 节点身份
     * @param attemptNumber 正数调用编号
     * @param targetState 成功、警告、失败或取消
     * @param outputs 可空结果，将复制
     * @param error 仅失败时必填
     * @throws IllegalArgumentException 编号或结果不合法时抛出
     */
    public WorkerTaskResult {
        if (attemptNumber < 1) throw new IllegalArgumentException("Attempt number must be positive");
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

    /**
     * 沿用调用信封的身份和编号创建结果。
     * @param task 实际调用信封，只读
     * @param targetState Worker 终态
     * @param outputs 可空业务结果，将复制
     * @param error 仅失败时必填
     * @return 关联原调用的结果
     */
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
            task.attemptNumber(),
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
