package org.cses.flow.core.services.executions;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.services.executions.queries.ExecutionQueryHandler;
import org.cses.flow.core.services.flows.queries.FlowQueryHandler;
import org.cses.flow.executor.commands.*;
import org.cses.flow.extensions.flow.Pause;
import org.cses.flow.queues.Queue;
import org.paas.common.util.StringUtil;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.*;
import java.util.stream.Collectors;

@Singleton
public class ExecutionService {

    private ExecutionQueryHandler queryHandler;
    private FlowQueryHandler flowQueryHandler;
    private Queue<ExecutionCommand> executorCommandQueue;

    /**
     * Retains public query collaborators and the annotation-selected command publisher.
     * @param queryHandler reads tenant-scoped execution snapshots
     * @param flowQueryHandler resolves exact published flow versions
     * @param executorCommandQueue accepts commands through the application-owned transport
     */
    @Inject
    public ExecutionService(
            ExecutionQueryHandler queryHandler,
            FlowQueryHandler flowQueryHandler,
            Queue<ExecutionCommand> executorCommandQueue
    ) {
        this.queryHandler = queryHandler;
        this.flowQueryHandler = flowQueryHandler;
        this.executorCommandQueue = executorCommandQueue;
    }

    /**
     * 启动一个 Execution。
     *
     * <p>当 {@code version} 为空时启动该 {@code key} 对应的最新 Flow；
     * 当 {@code version} 不为空时启动指定版本。方法返回的是 Executor
     * Command Queue 的受理回执，Execution 会由消费者异步创建并推进。</p>
     *
     * @param session 发起启动请求的租户和用户上下文
     * @param key     Flow 的业务标识，用于确定要启动的逻辑 Flow
     * @param version 要启动的 Flow 版本；为空表示使用最新版本
     * @param inputs  针对选定 Flow 版本提交的启动输入
     * @return Executor Command Queue 的受理回执
     */
    public <S extends Session<U>, U extends User> Create create(
            S session,
            String key,
            Optional<Long> version,
            Map<String, ?> inputs
    ) {
        return create(
                session,
                StringUtil.newId(),
                key,
                version,
                inputs
        );
    }

    /**
     * 使用调用方预先分配的稳定 ID 启动一个 Execution。
     *
     * <p>该入口用于宿主系统先在自己的事务中持久化 Execution 引用，再在事务提交后
     * 投递 Flow 启动命令。除 ID 已由调用方通过统一技术 ID 生成器分配外，Flow
     * 选择、输入规范化和 Queue 受理语义与普通 {@link #create(Session, String,
     * Optional, Map)} 完全一致；它不会提前物化一个待启动的 Execution。</p>
     *
     * @param session     发起启动请求的租户和用户上下文
     * @param executionId 已预先分配且将由 Flow 持久化的 Execution 技术 ID
     * @param key         Flow 的业务标识，用于确定要启动的逻辑 Flow
     * @param version     要启动的 Flow 版本；为空表示使用最新版本
     * @param inputs      针对选定 Flow 版本提交的启动输入
     * @return Executor Command Queue 的受理回执
     */
    public <S extends Session<U>, U extends User> Create create(
            S session,
            String executionId,
            String key,
            Optional<Long> version,
            Map<String, ?> inputs
    ) {
        Flow flow = requireFlow(session, key, version);
        Map<String, Object> normalizedInputs = flow.bindInputs(inputs);
        Create command = Create.from(
                session,
                executionId,
                flow.key(),
                flow.version(),
                normalizedInputs
        );
        executorCommandQueue.emit(command);
        return command;
    }

    public <S extends Session<U>, U extends User> Execution cancel(
            S session,
            String executionId
    ) {
        Execution current = queryHandler.execution(session, executionId)
                .orElseThrow(() -> new WorkflowException(
                        "Execution does not exist: " + executionId
                ));
        if (current.isTerminal()) {
            throw new WorkflowException(
                    "Cannot cancel terminal Execution: " + executionId
            );
        }
        if (current.state().is(State.Type.KILLING)) {
            throw new WorkflowException(
                    "Execution is already KILLING: " + executionId
            );
        }
        executorCommandQueue.emit(Cancel.from(session, executionId));
        return current.copy();
    }

    /**
     * Validates and submits one durable Resume command for an exact paused
     * TaskRun. Returning means Queue acceptance, not workflow completion.
     *
     * @param <S>         session type
     * @param <U>         session user type
     * @param session     current tenant and actor
     * @param executionId exact execution
     * @param taskRunId   exact paused occurrence
     * @param outputs     outputs validated against the Pause definition
     * @return queue-accepted current execution snapshot
     * @throws WorkflowException when the execution or occurrence cannot resume
     */
    public <S extends Session<U>, U extends User> Execution resume(
            S session, String executionId, String taskRunId, Map<String, ?> outputs
    ) {
        return submitResume(session, executionId, taskRunId, outputs, false);
    }

    /**
     * Accepts a resume during a Pause's pre-action and applies it after that action completes.
     * This supports automatic decisions without bypassing the mandatory pre-pause action.
     *
     * @param <S>         session type
     * @param <U>         session user type
     * @param session     current tenant and actor
     * @param executionId exact execution
     * @param taskRunId   exact Pause occurrence
     * @param outputs     outputs validated against the Pause resume contract
     * @return queue-accepted current execution snapshot
     * @throws WorkflowException when the target is neither paused nor awaiting its first pause
     */
    public <S extends Session<U>, U extends User> Execution resumeWhenPaused(
            S session, String executionId, String taskRunId, Map<String, ?> outputs
    ) {
        return submitResume(session, executionId, taskRunId, outputs, true);
    }

    /**
     * Validates an exact occurrence and publishes its durable resume command.
     *
     * @param <S>          session type
     * @param <U>          session user type
     * @param session      current tenant and actor
     * @param executionId  exact execution
     * @param taskRunId    exact Pause occurrence
     * @param outputs      requested resume outputs
     * @param waitForPause whether a pre-pause action may finish before application
     * @return queue-accepted current execution snapshot
     * @throws WorkflowException when the request is invalid
     */
    private <S extends Session<U>, U extends User> Execution submitResume(
            S session,
            String executionId,
            String taskRunId,
            Map<String, ?> outputs,
            boolean waitForPause
    ) {
        Execution current = queryHandler.execution(session, executionId)
                .orElseThrow(() -> new WorkflowException(
                        "Execution does not exist: " + executionId
                ));
        Map<String, Object> normalizedOutputs = validateResume(
                session,
                current,
                taskRunId,
                outputs,
                waitForPause
        );
        executorCommandQueue.emit(Resume.from(
                session,
                executionId,
                taskRunId,
                normalizedOutputs
        ));
        return current.copy();
    }

    /**
     * Validates and submits one durable Rewind command from the current Pause
     * to a selected historical TaskRun.
     *
     * <p>The result contains the new Execution ID, the source snapshot and the
     * TaskRuns affected by this request in business rollback order. It proves
     * Queue acceptance only; asynchronous command consumption may not have
     * applied the rewind when this method returns.</p>
     *
     * @param session         tenant and user context used to validate and enqueue rewind
     * @param executionId     nonterminal Execution with a paused source occurrence
     * @param sourceTaskRunId current Pause TaskRun that requests the rewind
     * @param targetTaskRunId completed historical TaskRun to restart from
     * @param reason          non-blank business reason recorded by the rewind command
     * @return queue-accepted rewind result based on the validated snapshot
     * @throws WorkflowException when the Execution or rewind path is invalid
     */
    public <S extends Session<U>, U extends User> RewindResult rewind(
            S session,
            String executionId,
            String sourceTaskRunId,
            String targetTaskRunId,
            String reason
    ) {
        return rewind(session, executionId, StringUtil.newId(), sourceTaskRunId, targetTaskRunId, reason);
    }

    /**
     * 使用调用方已持久化的派生实例 ID 提交回退命令。
     * 宿主先保存当前处理坐标，再调用本方法，避免新实例先回调而宿主尚未绑定。
     * @param session 当前租户和操作者
     * @param executionId 回退来源实例 ID
     * @param replayExecutionId 与来源不同的预分配新实例 ID
     * @param sourceTaskRunId 发起回退的暂停实例 ID
     * @param targetTaskRunId 重新执行的历史目标实例 ID
     * @param reason 非空回退原因
     * @return 队列受理结果，携带同一个预分配 ID；不表示新实例已运行
     * @throws WorkflowException 来源实例或回退路径不合法时抛出
     * @throws IllegalArgumentException 身份或原因不合法时抛出
     */
    public <S extends Session<U>, U extends User> RewindResult rewind(
            S session,
            String executionId,
            String replayExecutionId,
            String sourceTaskRunId,
            String targetTaskRunId,
            String reason
    ) {
        RewindPlan plan = planRewind(
                session,
                executionId,
                sourceTaskRunId,
                targetTaskRunId
        );
        Rewind command = Rewind.from(
                session,
                executionId,
                replayExecutionId,
                sourceTaskRunId,
                targetTaskRunId,
                reason
        );
        executorCommandQueue.emit(command);
        return RewindResult.from(
                command.getReplayExecutionId(),
                plan.execution(),
                plan.affectedTaskRunIds()
        );
    }

    /**
     * Validates a prospective rewind and calculates its exact impact without
     * writing to the Executor command queue.
     *
     * <p>The Execution and Flow are read before the plan is returned. No Flow
     * mutation occurs, so a host can release this read and then commit its own
     * local transaction before calling {@link #rewind(Session, String, String,
     * String, String)}.</p>
     *
     * @param session         tenant and user context used to read the Flow state
     * @param executionId     nonterminal Execution with a paused source occurrence
     * @param sourceTaskRunId current Pause TaskRun that requests the rewind
     * @param targetTaskRunId completed historical TaskRun to restart from
     * @return validated read-only rewind plan and affected TaskRun IDs
     * @throws WorkflowException when the Execution or rewind path is invalid
     */
    public <S extends Session<U>, U extends User> RewindPlan planRewind(
            S session,
            String executionId,
            String sourceTaskRunId,
            String targetTaskRunId
    ) {
        Execution current = queryHandler.execution(session, executionId)
                .orElseThrow(() -> new WorkflowException(
                        "Execution does not exist: " + executionId
                ));
        Flow flow = validateRewind(
                session,
                current,
                sourceTaskRunId,
                targetTaskRunId
        );
        List<String> affectedTaskRunIds = affectedTaskRunIds(
                flow,
                current,
                sourceTaskRunId,
                targetTaskRunId
        );
        TaskRun target = current.requireTaskRun(targetTaskRunId);
        String targetTaskKey = flow.findTask(target.taskId())
                .map(Task::key)
                .orElseThrow(() -> new WorkflowException(
                        "Task definition does not exist: " + target.taskId()
                ));
        return RewindPlan.from(
                current,
                targetTaskKey,
                affectedTaskRunIds
        );
    }

    public <S extends Session<U>, U extends User>
    Optional<Execution> execution(
            S session,
            String executionId
    ) {

        return queryHandler.execution(session, executionId);
    }

    public <S extends Session<U>, U extends User>
    List<Execution> executions(S session) {

        return queryHandler.executions(session);
    }

    /**
     * Reads all Execution snapshots belonging to the same origin as the selected instance.
     * @param session tenant-scoped caller
     * @param executionId accessible member of the derivation tree
     * @return complete snapshots with explicit origins and inherited run boundaries
     * @throws WorkflowException when the selected Execution cannot be read
     */
    public <S extends Session<U>, U extends User> List<Execution> lineage(S session, String executionId) {
        return queryHandler.lineage(session, executionId);
    }

    /**
     * 根据 Flow 的业务 key 和可选版本取得可启动的 Flow。
     *
     * @param session 当前租户和用户上下文，租户范围从中获取
     * @param key     Flow 的业务标识
     * @param version 要查询的 Flow 版本；为空查询最新版本
     * @return 指定或最新的未删除正式 Flow
     */
    private <S extends Session<U>, U extends User> Flow requireFlow(
            S session,
            String key,
            Optional<Long> version
    ) {
        Objects.requireNonNull(version, "Flow version must not be null");
        Optional<Flow> resolved = version.isPresent()
                ? flowQueryHandler.flow(
                session,
                key,
                version.orElseThrow()
        )
                : flowQueryHandler.latestFlow(session, key);
        Flow flow = resolved.orElseThrow(() -> new WorkflowException(
                version.isPresent()
                        ? "Flow version does not exist: "
                        + key + "@" + version.orElseThrow()
                        : "Flow does not exist: " + key
        ));
        if (flow.deleted()) {
            throw new WorkflowException(
                    "Only an undeleted Flow can start an Execution: "
                            + flow.key() + "@" + flow.version()
            );
        }
        return flow;
    }

    /**
     * Validates the exact paused occurrence even while a parallel branch is running.
     *
     * @param <S>          session type
     * @param <U>          session user type
     * @param session      current tenant and actor
     * @param execution    complete execution snapshot
     * @param taskRunId    exact paused occurrence
     * @param outputs      caller-provided resume outputs
     * @param waitForPause whether to accept the first pre-pause interval
     * @return outputs validated against the bound Pause definition
     * @throws WorkflowException when the execution or selected occurrence cannot resume
     */
    private <S extends Session<U>, U extends User>
    Map<String, Object> validateResume(
            S session,
            Execution execution,
            String taskRunId,
            Map<String, ?> outputs,
            boolean waitForPause
    ) {
        if (!execution.canResumeTaskRun()) {
            throw new WorkflowException(
                    "Only a RUNNING, RESTARTED or PAUSED Execution can resume a Pause TaskRun: "
                            + execution.id()
            );
        }
        Flow flow = flowQueryHandler.flow(
                        session,
                        execution.flowKey(),
                        execution.flowVersion()
                )
                .orElseThrow(() -> new WorkflowException(
                        "Flow does not exist: " + execution.flowKey()
                                + "@" + execution.flowVersion()
                ));
        TaskRun taskRun = execution.requireTaskRun(taskRunId);
        if (!taskRun.state().is(State.Type.PAUSED)
                && !(waitForPause && execution.isAwaitingPause(taskRunId))) {
            throw new WorkflowException(
                    "Only a PAUSED TaskRun can be resumed: " + taskRun.id()
            );
        }
        Task task = flow.findTask(taskRun.taskId()).orElseThrow(() ->
                new WorkflowException(
                        "Task definition does not exist: " + taskRun.taskId()
                )
        );
        if (!(task instanceof Pause pause)) {
            throw new WorkflowException(
                    "Only a paused Orchestration TaskRun can be resumed: "
                            + taskRun.id()
            );
        }
        return pause.bindResume(outputs);
    }

    /**
     * Loads the execution-bound Flow and validates the requested effective rewind path.
     *
     * @param session         authenticated tenant context used for the read
     * @param execution       current snapshot to validate
     * @param sourceTaskRunId effective paused source occurrence ID
     * @param targetTaskRunId effective completed preceding occurrence ID
     * @return the exact Flow version bound to the execution
     * @throws WorkflowException when the Flow or rewind path is unavailable
     */
    private <S extends Session<U>, U extends User> Flow validateRewind(
            S session,
            Execution execution,
            String sourceTaskRunId,
            String targetTaskRunId
    ) {
        Flow flow = flowQueryHandler.flow(
                        session,
                        execution.flowKey(),
                        execution.flowVersion()
                )
                .orElseThrow(() -> new WorkflowException(
                        "Flow does not exist: " + execution.flowKey()
                                + "@" + execution.flowVersion()
                ));
        validateRewind(flow, execution, sourceTaskRunId, targetTaskRunId);
        return flow;
    }

    /**
     * Validates a paused source and completed causal predecessor without mutating either snapshot.
     *
     * @param flow            execution-bound definition tree
     * @param execution       current snapshot to read
     * @param sourceTaskRunId effective paused source occurrence ID
     * @param targetTaskRunId effective completed preceding occurrence ID
     * @throws WorkflowException when the endpoints are stale, unordered, unsupported or the execution is terminal
     */
    public static void validateRewind(
            Flow flow,
            Execution execution,
            String sourceTaskRunId,
            String targetTaskRunId
    ) {
        TaskRun source = execution.requireTaskRun(sourceTaskRunId);
        TaskRun target = execution.requireTaskRun(targetTaskRunId);
        if (!source.state().is(State.Type.PAUSED)) {
            throw new WorkflowException(
                    "Rewind source must be a PAUSED TaskRun: " + source.id()
            );
        }
        Task sourceTask = flow.findTask(source.taskId()).orElseThrow(() ->
                new WorkflowException(
                        "Task definition does not exist: " + source.taskId()
                )
        );
        if (!(sourceTask instanceof Pause)) {
            throw new WorkflowException(
                    "Rewind source must be a Pause TaskRun: " + source.id()
            );
        }
        if (!target.state().is(State.Type.SUCCESS)
                && !target.state().is(State.Type.WARNING)) {
            throw new WorkflowException(
                    "Rewind target must be a completed TaskRun: " + target.id()
            );
        }
        if (execution.isTerminal() || execution.state().is(State.Type.KILLING)) {
            throw new WorkflowException("Execution cannot rewind: " + execution.id());
        }
        RewindPath.requireNonIterated(flow, source);
        RewindPath.requireNonIterated(flow, target);
        if (!RewindPath.precedes(flow, target.taskId(), source.taskId())) {
            throw new WorkflowException("Rewind target must precede its source on the same execution path");
        }
        Set<String> effectiveTaskRunIds = execution.effectiveTaskRuns().stream()
                .map(TaskRun::id)
                .collect(Collectors.toSet());
        if (!effectiveTaskRunIds.contains(source.id())
                || !effectiveTaskRunIds.contains(target.id())) {
            throw new WorkflowException(
                    "Rewind source and target must belong to the current "
                            + "effective execution path"
            );
        }
    }

    /**
     * Validates a rewind and returns the precise business invalidation scope.
     *
     * @param flow            execution-bound definition tree
     * @param execution       current snapshot to read
     * @param sourceTaskRunId effective paused source occurrence ID
     * @param targetTaskRunId effective completed preceding occurrence ID
     * @return immutable affected occurrence IDs in rollback order
     * @throws WorkflowException when the requested path cannot rewind
     */
    public static List<String> affectedTaskRunIds(
            Flow flow,
            Execution execution,
            String sourceTaskRunId,
            String targetTaskRunId
    ) {
        validateRewind(flow, execution, sourceTaskRunId, targetTaskRunId);
        return RewindPath.affectedTaskRunIds(flow, execution, targetTaskRunId);
    }

    /**
     * Reads the nearest completed host-selected tasks on the effective branch path.
     *
     * @param session         authenticated tenant context used for reads
     * @param executionId     nonterminal execution to inspect
     * @param sourceTaskRunId currently paused effective source occurrence ID
     * @param taskKeys        host-owned candidate definition keys to read without mutation
     * @return immutable list from the loaded snapshot; empty when the source is no longer effective or no predecessor exists
     * @throws WorkflowException when execution, Flow or source is missing
     */
    public <S extends Session<U>, U extends User> List<TaskRun> previousCompletedTaskRuns(
            S session, String executionId, String sourceTaskRunId, Set<String> taskKeys
    ) {
        Execution execution = queryHandler.execution(session, executionId).orElseThrow(() ->
                new WorkflowException("Execution does not exist: " + executionId));
        Flow flow = flowQueryHandler.flow(session, execution.flowKey(), execution.flowVersion())
                .orElseThrow(() -> new WorkflowException("Flow does not exist: " + execution.flowKey()));
        TaskRun source = execution.requireTaskRun(sourceTaskRunId);
        if (!source.isPaused() || execution.isTerminal()
                || execution.effectiveTaskRuns().stream().noneMatch(run -> run.identifiedBy(sourceTaskRunId))) {
            return List.of();
        }
        List<TaskRun> candidates = execution.effectiveTaskRuns().stream()
                .filter(run -> run.state().is(State.Type.SUCCESS) || run.state().is(State.Type.WARNING))
                .filter(run -> flow.findTask(run.taskId()).map(task -> taskKeys.contains(task.key())).orElse(false))
                .filter(run -> RewindPath.precedesInBranch(flow, run.taskId(), source.taskId()))
                .toList();
        return candidates.stream().filter(candidate -> candidates.stream().noneMatch(later ->
                RewindPath.precedesInBranch(flow, candidate.taskId(), later.taskId()))).toList();
    }

}
