package org.cses.flow.core.runner;

import java.util.Objects;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.tasks.Task;

/** 下一步任务的定义及对应运行记录，不携带状态决定或轮次请求。 */
public record ResolvedNextTask(Task task, TaskRun taskRun) {
    /**
     * 校验任务与运行记录属于同一定义；调用方不得通过引用修改运行事实。
     * @param task 任务定义，非 null
     * @param taskRun 对应运行记录，非 null
     * @throws IllegalArgumentException 定义编号不一致时抛出
     */
    public ResolvedNextTask {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(taskRun, "taskRun");
        if (!task.id().equals(taskRun.taskId())) {
            throw new IllegalArgumentException("TaskRun does not belong to Task: " + task.id());
        }
    }

    /**
     * 组合任务定义与对应运行记录，不复制或修改它们。
     * @param task 任务定义，非 null
     * @param taskRun 对应运行记录，非 null
     * @return 保存原对象引用的解析结果
     */
    public static ResolvedNextTask from(Task task, TaskRun taskRun) {
        return new ResolvedNextTask(task, taskRun);
    }
}
