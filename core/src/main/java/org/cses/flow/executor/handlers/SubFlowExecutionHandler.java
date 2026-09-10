package org.cses.flow.executor.handlers;

import jakarta.inject.Singleton;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.executor.ExecutorContext;
import org.cses.flow.executor.ExecutorEvent;
import org.cses.flow.executor.ExecutorService;
import org.cses.flow.queues.Queue;
import org.jooq.DSLContext;
import org.jooq.exception.DataChangedException;
import org.paas.session.Session;

import java.util.Map;

/** 协调子运行创建与父调用完成；领域迁移仍由 Execution 和 ExecutorService 承担。 */
@Singleton
public class SubFlowExecutionHandler {
    private FlowRepository flows;
    private ExecutionRepository executions;
    private ExecutorService executor;
    private Queue<ExecutorEvent> events;

    /**
     * 注入子调用使用的已有运行设施。
     * @param flows 流程版本仓储
     * @param executions 完整运行仓储
     * @param executor 运行状态机
     * @param events 持久化后的调度事件发布器
     */
    public SubFlowExecutionHandler(FlowRepository flows, ExecutionRepository executions,
            ExecutorService executor, Queue<ExecutorEvent> events) {
        this.flows = flows;
        this.executions = executions;
        this.executor = executor;
        this.events = events;
    }

    /**
     * 重读父运行后绑定参数，原子保存调用节点与独立子运行，再发布子调度事件。
     * @param dsl 当前数据库上下文
     * @param session 父运行的可信会话
     * @param context 父流程定义与调度计划
     * @param taskRunId 精确调用节点
     * @return 保存后的父运行上下文，或已由另一调度处理的最新快照
     * @throws RuntimeException 存储或事件发布失败时抛出，不将设施故障改写为业务失败
     */
    public ExecutorContext start(DSLContext dsl, Session<?> session,
            ExecutorContext context, String taskRunId) {
        Execution parent = executions.findById(dsl, context.execution().companyId(), context.execution().id())
                .orElseThrow(() -> new WorkflowException("Parent execution does not exist"));
        ExecutorContext current = new ExecutorContext(context.flow(), parent);
        if (!parent.state().is(State.Type.RUNNING)
                || !parent.requireTaskRun(taskRunId).state().is(State.Type.CREATED)) return current;
        Task task = context.flow().findTask(parent.requireTaskRun(taskRunId).taskId()).orElseThrow();
        var reference = ((OrchestrationTask<?>) task).subFlow().orElseThrow();
        Flow childFlow;
        Map<String, Object> inputs;
        try {
            Flow latest = flows.findLatestByFlowId(dsl, FlowId.from(parent.companyId(), reference.key()))
                    .orElseThrow(() -> new WorkflowException("SubFlow does not exist: " + reference.key()));
            if (latest.deleted()) throw new WorkflowException("SubFlow is deleted: " + reference.key());
            childFlow = flows.findByFlowId(dsl,
                    FlowId.from(parent.companyId(), reference.key(), reference.version()))
                    .orElseThrow(() -> new WorkflowException("SubFlow version does not exist: " + reference));
            if (childFlow.deleted()) throw new WorkflowException("SubFlow version is deleted: " + reference);
            inputs = childFlow.bindInputs(task.bindInputs(parent.inputs()));
        } catch (IllegalArgumentException | WorkflowException failure) {
            parent.startTaskRun(taskRunId);
            parent.failTaskRun(taskRunId, "SubFlow could not start: " + failure.getMessage());
            executions.save(dsl, parent);
            return current;
        }
        Execution child = parent.startSubFlow(session, taskRunId,
                childFlow.key(), childFlow.reversion(), inputs);
        executions.save(dsl, parent, child);
        events.emit(ExecutorEvent.from(child, ExecutorEvent.EventType.CREATED));
        return current;
    }

    /**
     * 将子终态合并到最新父快照，并唤醒父流程继续调度。
     * @param dsl 当前数据库上下文
     * @param childContext 已处理并持久化的子运行
     * @throws RuntimeException 定义缺失、结果非法或存储失败时抛出
     */
    public void complete(DSLContext dsl, ExecutorContext childContext) {
        Execution child = childContext.execution();
        if (!child.isTerminal() || child.parentTaskRunId() == null) return;
        while (true) {
            Execution parent = executions.findById(dsl, child.companyId(), child.origin().parentId())
                    .orElseThrow(() -> new WorkflowException("SubFlow parent does not exist"));
            if (!parent.state().is(State.Type.RUNNING)
                    || parent.effectiveTaskRuns().stream().noneMatch(run ->
                    run.identifiedBy(child.parentTaskRunId()) && run.state().is(State.Type.RUNNING))) return;
            Flow parentFlow = flows.findByFlowId(dsl,
                    FlowId.from(parent.companyId(), parent.flowKey(), parent.flowVersion())).orElseThrow();
            ExecutorContext parentContext = new ExecutorContext(parentFlow, parent);
            executor.completeSubFlow(parentContext, childContext.flow(), child);
            try {
                executions.save(dsl, parent);
            } catch (DataChangedException conflict) {
                // 并行分支只重做最新快照上的结果合并，不重新调用子流程。
                continue;
            }
            events.emit(ExecutorEvent.from(parent, ExecutorEvent.EventType.UPDATED));
            return;
        }
    }
}
