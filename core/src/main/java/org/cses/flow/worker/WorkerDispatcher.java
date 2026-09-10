package org.cses.flow.worker;

import jakarta.inject.Singleton;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.domains.tasks.Output;

import java.util.Objects;

/**
 * Generic Worker boundary that directly invokes a RunnableTask.
 */
@Singleton
public class WorkerDispatcher {

    /**
     * 执行任务并将具体输出转为队列结果，意外异常继续交给调用边界处理。
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
        Output result = Objects.requireNonNull(
            workerTask.runnableTask().run(context),
            "RunnableTask result"
        );
        return WorkerTaskResult.from(workerTask, result);
    }
}
