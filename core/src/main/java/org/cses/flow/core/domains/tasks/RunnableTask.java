package org.cses.flow.core.domains.tasks;

import org.cses.flow.core.runner.RunContext;

/**
 * Executable capability implemented by a concrete Task definition.
 */
public interface RunnableTask<T extends Output> {

    /**
     * Executes this Task once using only its invocation-scoped context.
     */
    T run(RunContext context);

    /**
     * 声明是否可在结果不确定时按原 Execution、TaskRun 和 Attempt 安全重放，默认拒绝。
     * @return true 表示实现保证幂等或可重建确定结果，false 禁止未知效果下自动再次调用
     */
    default boolean canReplay() { return false; }
}
