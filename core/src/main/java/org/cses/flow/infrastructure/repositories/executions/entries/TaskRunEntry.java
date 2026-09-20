package org.cses.flow.infrastructure.repositories.executions.entries;

import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.infrastructure.repositories.executions.codec.StateJsonCodec;
import org.cses.flow.infrastructure.repositories.executions.codec.GenerationJsonCodec;
import org.cses.flow.infrastructure.repositories.executions.codec.TaskRunValuesJsonCodec;
import org.cses.flow.infrastructure.repositories.executions.codec.TaskRunAttemptsJsonCodec;
import org.flow.gen.flow.pojos.TaskRunsObject;

public class TaskRunEntry extends TaskRunsObject {

    /**
     * 转换完整任务事实，包含尝试历史与待重试时间。
     * @param executionId 所属实例身份，继承快照可为 null
     * @param taskRun 不修改的领域节点
     * @param order 节点顺序，从 0 开始
     * @return 新建数据库映射
     */
    public static TaskRunEntry from(
        String executionId,
        TaskRun taskRun,
        int order
    ) {
        TaskRunEntry entry = new TaskRunEntry();
        entry.id = taskRun.id();
        entry.executionId = executionId;
        entry.taskId = taskRun.taskId();
        entry.parentId = taskRun.parentId().orElse(null);
        entry.iteration = taskRun.iteration().isPresent()
            ? taskRun.iteration().getAsInt()
            : null;
        entry.executionGenerationVersion = taskRun
            .executionGenerationVersion()
            .isPresent()
            ? taskRun.executionGenerationVersion().getAsInt()
            : null;
        entry.generation = GenerationJsonCodec.encode(taskRun.generation());
        entry.state = StateJsonCodec.encode(taskRun.state());
        entry.inputs = TaskRunValuesJsonCodec.encode(taskRun.inputs());
        entry.outputs = TaskRunValuesJsonCodec.encode(taskRun.outputs());
        entry.error = taskRun.error().orElse(null);
        entry.attempts = TaskRunAttemptsJsonCodec.encode(taskRun.attempts());
        entry.retryVariables = TaskRunValuesJsonCodec.encodeVariables(taskRun.retryVariables());
        entry.nextRetryAt = taskRun.nextRetryAt();
        entry.order = order;
        return entry;
    }

    /**
     * 从当前数据库结构恢复完整节点，不生成新的尝试或时间。
     * @return 独立领域节点
     * @throws IllegalArgumentException 存储事实不合法时抛出
     */
    public TaskRun to() {
        return TaskRun.rehydrate(
            id,
            taskId,
            parentId,
            iteration,
            executionGenerationVersion,
            TaskRunValuesJsonCodec.decode(inputs),
            GenerationJsonCodec.decode(generation),
            StateJsonCodec.decode(state),
            TaskRunValuesJsonCodec.decode(outputs),
            error,
            TaskRunAttemptsJsonCodec.decode(attempts),
            TaskRunValuesJsonCodec.decode(retryVariables),
            nextRetryAt
        );
    }
}
