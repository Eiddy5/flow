package org.cses.flow.core.domains.tasks;

import java.util.List;
import java.util.Optional;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.runner.OrchestrationContext;
import org.cses.flow.core.runner.ResolvedNextTask;

/** 当前 Execution 内的编排行为；解析任务和状态，不修改运行记录。 */
public interface OrchestrationTask<T extends Output> {
    /**
     * 根据当前运行事实解析下一批直接子任务。
     * @param context 当前节点的只读上下文，非 null
     * @return 任务定义与运行记录列表；没有可启动任务时为空
     */
    List<ResolvedNextTask> resolveNexts(OrchestrationContext context);

    /**
     * 根据当前运行事实判断节点是否应转换状态。
     * @param context 当前节点的只读上下文，非 null
     * @return 目标状态；空表示继续等待，不修改当前节点
     */
    Optional<State.Type> resolveState(OrchestrationContext context);
}
