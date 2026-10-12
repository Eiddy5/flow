package org.cses.flow.executor;

import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.repositories.worker.ExecutionMessage;
import org.cses.flow.core.repositories.worker.WorkerDelivery;
import org.cses.flow.core.repositories.worker.WorkerRuntimeRepository;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.cses.flow.worker.WorkerCommand;
import org.cses.flow.worker.WorkerResultEvent;
import org.cses.flow.worker.WorkerTask;
import org.cses.flow.worker.WorkerTaskResult;
import org.jooq.DSLContext;
import org.jooq.exception.DataChangedException;
import org.x9.jooq.JOOQ;

/** Executor 独占业务状态写入，短事务协调 Worker 领取和可靠结果接纳。 */
@Singleton
public class WorkerExecutionCoordinator {
    private JOOQ jooq;
    private ExecutionRepository executions;
    private FlowRepository flows;
    private WorkerRuntimeRepository runtime;
    private ExecutorService executor;

    /** 注入数据库及聚合仓储，Runner 和 Broker 均不进入事务。
     * @param jooq 具名 Flow 数据库访问组件，不取得其关闭权
     * @param executions 非空完整 Execution 聚合仓储
     * @param flows 非空精确已发布流程定义仓储
     * @param runtime 非空技术许可和待消费消息仓储
     * @param executor 非空 Executor 领域状态机，不执行 Runner
     */
    public WorkerExecutionCoordinator(@Named(FlowDatabase.DATA_SOURCE_NAME) JOOQ jooq,
            ExecutionRepository executions, FlowRepository flows, WorkerRuntimeRepository runtime, ExecutorService executor) {
        this.jooq = jooq;
        this.executions = executions;
        this.flows = flows;
        this.runtime = runtime;
        this.executor = executor;
    }

    /** 领取的原尝试许可；task 为 null 表示补发已有结果或确认失效命令。 */
    public record Start(WorkerDelivery delivery, WorkerTask task, ExecutionMessage result) {
        /**
         * 返回领取快照，空任务表示已有结果或命令已失效。
         * @param delivery 当前尝试的完整许可快照；领取结果构建时允许 null
         * @param task 领取恢复的调用快照，已有结果或失效命令时允许 null
         * @param result 已完成的结果或待补发快照；领取结果构建时允许 null
         * @return 保留传入身份的完整快照，不生成新的业务身份
         */
        public static Start from(WorkerDelivery delivery, WorkerTask task, ExecutionMessage result) {
            return new Start(delivery, task, result);
        }
    }

    /**
     * 在原聚合 CAS 成功后确认开始；重复投递和接管不新增业务尝试。
     * @param command 非空的原任务命令，读取稳定身份
     * @param ownerId 非空白的本次 Worker 所有者身份
     * @param leaseMillis 租约长度，毫秒；Worker 要求至少 3000ms
     * @return 原尝试领取快照；空任务表示已有结果或命令已失效
     * @throws IllegalStateException 许可仍活跃、恢复不安全或当前结果缺失时抛出，调用方须让队列重投
     */
    public Start start(WorkerCommand command, String ownerId, long leaseMillis) {
        while (true) {
            try {
                return runtime.transaction(jooq.createDSLContext(), dsl -> start(dsl, command, ownerId, leaseMillis));
            } catch (DataChangedException conflict) {
                // 丢弃本轮已修改对象，重新核验当前聚合与许可。
            }
        }
    }

    /**
     * 核验当前许可，首次领取开始 Attempt，租约接管仅增加技术代次。
     * @param dsl 调用方拥有的非空 Flow 数据库上下文，不关闭其连接
     * @param command 非空的原任务命令，读取稳定身份
     * @param ownerId 非空白的本次 Worker 所有者身份
     * @param leaseMillis 租约长度，毫秒；Worker 要求至少 3000ms
     * @return 原尝试领取快照；空任务表示已有结果或命令已失效
     */
    private Start start(DSLContext dsl, WorkerCommand command, String ownerId, long leaseMillis) {
        WorkerDelivery delivery = runtime.delivery(dsl, command.companyId(), command.executionId(), command.taskRunId(), true).orElse(null);
        Execution execution = executions.findById(dsl, command.companyId(), command.executionId()).orElse(null);
        if (execution == null || execution.isTerminal() || execution.state().is(State.Type.KILLING)) return Start.from(null, null, null);
        var run = execution.findTaskRun(command.taskRunId()).orElse(null);
        if (run == null || command.attemptNumber() < run.attemptNumber()) return Start.from(null, null, null);
        if (delivery != null && delivery.attemptNumber() == command.attemptNumber()) {
            if (delivery.phase() == WorkerDelivery.Phase.SETTLED) return Start.from(delivery, null, null);
            if (delivery.phase() == WorkerDelivery.Phase.REPORTED) {
                var result = runtime.message(dsl, command.companyId(), delivery.resultId())
                    .orElseThrow(() -> new IllegalStateException("Reported Worker result is missing"));
                return Start.from(delivery, null, result);
            }
        }
        var context = context(dsl, execution);
        var task = context.flow().findTask(run.taskId()).orElseThrow();
        WorkerTask planned = ExecutorService.workerTask(context, run, task);
        long now = TimeUtil.now();
        long generation = 1;
        WorkerTask actual;
        if (run.state().is(State.Type.SUBMITTED) && command.attemptNumber() == run.attemptNumber() + 1) {
            var dispatched = executor.dispatch(context, planned);
            executions.save(dsl, execution);
            if (dispatched.isEmpty()) return Start.from(null, null, null);
            actual = dispatched.get();
        } else if (run.state().is(State.Type.RUNNING) && command.attemptNumber() == run.attemptNumber()
                && delivery != null && delivery.attemptNumber() == command.attemptNumber()
                && delivery.phase() == WorkerDelivery.Phase.RUNNING) {
            if (delivery.leaseUntilAt() != null && delivery.leaseUntilAt() > now) {
                throw new IllegalStateException("Worker attempt is still owned by a live lease");
            }
            if (!planned.runnableTask().canReplay()) {
                throw new IllegalStateException("Uncertain external effect requires task reconciliation before replay");
            }
            generation = Math.incrementExact(delivery.deliveryGeneration());
            executions.save(dsl, execution);
            actual = planned;
        } else {
            return Start.from(null, null, null);
        }
        WorkerDelivery claimed = WorkerDelivery.from(command.companyId(), command.executionId(), command.taskRunId(),
            command.attemptNumber(), generation, ownerId, Math.addExact(now, leaseMillis), WorkerDelivery.Phase.RUNNING, null);
        runtime.saveDelivery(dsl, claimed);
        return Start.from(claimed, actual, null);
    }

    /**
     * 仅当前未过期 owner 保存结果和结果 outbox，旧代次不能覆盖当前事实。
     * @param delivery 非空的原领取许可，只读且须保持 owner 与代次
     * @param result 非空的原尝试确定结果，只读
     * @return 与许可同事务保存的独立结果消息
     * @throws IllegalStateException 当前 owner、代次或租期不再有效时拒绝保存
     * @throws IllegalArgumentException 结果运行、节点或尝试身份不匹配时抛出
     */
    public ExecutionMessage reported(WorkerDelivery delivery, WorkerTaskResult result) {
        return runtime.transaction(jooq.createDSLContext(), dsl -> {
            var current = runtime.delivery(dsl, delivery.companyId(), delivery.executionId(), delivery.taskRunId(), true).orElseThrow();
            if (!samePermit(current, delivery) || current.phase() != WorkerDelivery.Phase.RUNNING
                    || current.leaseUntilAt() == null || current.leaseUntilAt() <= TimeUtil.now()) {
                throw new IllegalStateException("Worker result no longer owns the current lease");
            }
            if (!delivery.executionId().equals(result.executionId()) || !delivery.taskRunId().equals(result.taskRunId())
                    || delivery.attemptNumber() != result.attemptNumber()) throw new IllegalArgumentException("Worker result identity mismatch");
            var message = ExecutionMessage.result(delivery.companyId(), delivery.deliveryGeneration(), result);
            runtime.add(dsl, message);
            runtime.saveDelivery(dsl, current.reported(WorkerDelivery.Phase.REPORTED, message.id()));
            return message;
        });
    }

    /**
     * 结果与业务状态、后续推进和结果删除一并提交，成功返回才允许 ACK。
     * @param event 非空结果引用，精确关联原尝试和代次
     * @throws RuntimeException 接纳失败时抛出，事务回滚且不允许结果 ACK
     */
    public void accept(WorkerResultEvent event) {
        while (true) {
            try {
                runtime.transaction(jooq.createDSLContext(), dsl -> { accept(dsl, event); return null; });
                return;
            } catch (DataChangedException conflict) {
                // 只重试接纳已有结果，不再次执行 Runner。
            }
        }
    }

    /**
     * 在当前许可锁内去重、拒绝迟到结果并应用原尝试终态。
     * @param dsl 调用方拥有的非空 Flow 数据库上下文，不关闭其连接
     * @param event 非空结果引用，只读取稳定身份和投递代次
     */
    private void accept(DSLContext dsl, WorkerResultEvent event) {
        var delivery = runtime.delivery(dsl, event.companyId(), event.executionId(), event.taskRunId(), true).orElse(null);
        var message = runtime.message(dsl, event.companyId(), event.id()).orElse(null);
        if (message == null) {
            if (delivery == null || delivery.attemptNumber() > event.attemptNumber()
                    || delivery.deliveryGeneration() > event.deliveryGeneration()
                    || delivery.phase() == WorkerDelivery.Phase.SETTLED && event.id().equals(delivery.resultId())) return;
            throw new IllegalStateException("Current Worker result is missing before acceptance");
        }
        if (message.kind() != ExecutionMessage.Kind.RESULT || !event.executionId().equals(message.executionId())
                || !event.taskRunId().equals(message.taskRunId()) || event.attemptNumber() != message.attemptNumber()
                || event.deliveryGeneration() != message.deliveryGeneration()) throw new IllegalArgumentException("Result reference identity mismatch");
        if (delivery == null || delivery.attemptNumber() != event.attemptNumber()
                || delivery.deliveryGeneration() != event.deliveryGeneration() || !event.id().equals(delivery.resultId())) {
            runtime.consumed(dsl, event.companyId(), event.id());
            return;
        }
        var execution = executions.findById(dsl, event.companyId(), event.executionId()).orElse(null);
        if (execution != null) {
            var result = message.result();
            if (!event.executionId().equals(result.executionId()) || !event.taskRunId().equals(result.taskRunId())
                    || event.attemptNumber() != result.attemptNumber()) throw new IllegalArgumentException("Result body identity mismatch");
            var context = context(dsl, execution);
            executor.applyResult(context, result);
            if (context.takeExecutionUpdated()) executions.save(dsl, execution);
        }
        runtime.saveDelivery(dsl, delivery.reported(WorkerDelivery.Phase.SETTLED, event.id()));
        runtime.consumed(dsl, event.companyId(), event.id());
    }

    /**
     * 恢复实例绑定的精确已发布定义。
     * @param dsl 调用方拥有的非空 Flow 数据库上下文，不关闭其连接
     * @param execution 非空已加载的完整聚合，保留加载版本
     * @return 绑定精确已发布版本的独立调度上下文
     */
    private ExecutorContext context(DSLContext dsl, Execution execution) {
        var flow = flows.findByFlowId(dsl, FlowId.from(execution.companyId(), execution.flowKey(), execution.flowVersion())).orElseThrow();
        return new ExecutorContext(flow, execution);
    }

    /**
     * 比较技术代次、业务尝试和 owner，租期由当前数据库事实决定。
     * @param current 数据库恢复的非空当前许可，读取而不修改
     * @param expected 领取时的非空许可快照，读取 owner 和代次
     * @return true 表示尝试编号、技术代次和 owner 全部相同
     */
    private boolean samePermit(WorkerDelivery current, WorkerDelivery expected) {
        return current.attemptNumber() == expected.attemptNumber() && current.deliveryGeneration() == expected.deliveryGeneration()
            && java.util.Objects.equals(current.ownerId(), expected.ownerId());
    }
}
