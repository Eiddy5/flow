package org.cses.flow.worker;

import jakarta.inject.Singleton;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.domains.tasks.Output;

import java.util.Objects;

/**
 * Generic Worker boundary that directly invokes a RunnableTask.
 */
@Singleton
public class WorkerDispatcher {

    /**
     * 执行任务并将具体输出转为队列结果，将 WorkflowException 转为失败信封；
     * 其他异常继续交给调用边界处理。
     * @param workerTask 非 null 的本次任务与变量快照，只读
     * @return 包含任务输出及终态的结果信封
     * @throws NullPointerException 任务或 run 返回值为 null 时抛出
     * @throws IllegalArgumentException 输出状态或失败原因不合法时抛出
     */
    public WorkerTaskResult dispatch(WorkerTask workerTask) {
        Objects.requireNonNull(workerTask, "workerTask");
        RunContext context = RunContext.builder()
            .variables(workerTask.variables())
            .build();
        Output result;
        try {
            result = workerTask.runnableTask().run(context);
        } catch (WorkflowException exception) {
            String error = exception.getMessage();
            return WorkerTaskResult.failed(workerTask,
                error == null || error.isBlank() ? exception.getClass().getSimpleName() : error);
        }
        Objects.requireNonNull(result, "RunnableTask result");
        return WorkerTaskResult.from(workerTask, result);
    }
}
