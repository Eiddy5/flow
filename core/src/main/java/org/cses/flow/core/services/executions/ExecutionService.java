package org.cses.flow.core.services.executions;

import jakarta.inject.Inject;
import jakarta.inject.Named;
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
import org.cses.flow.queues.DispatchQueue;
import org.paas.common.util.StringUtil;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.*;
import java.util.stream.Collectors;

@Singleton
public class ExecutionService {

    private final ExecutionQueryHandler queryHandler;
    private final FlowQueryHandler flowQueryHandler;
    private final DispatchQueue<ExecutionCommand> executorCommandQueue;

    @Inject
    public ExecutionService(
            ExecutionQueryHandler queryHandler,
            FlowQueryHandler flowQueryHandler,
            @Named(ExecutionCommand.QUEUE_NAME)
            DispatchQueue<ExecutionCommand> executorCommandQueue
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
        Map<String, Object> normalizedInputs = flow.normalizeInputs(inputs);
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
     */
    public <S extends Session<U>, U extends User> Execution resume(
            S session,
            String executionId,
            String taskRunId,
            Map<String, ?> outputs
    ) {
        Execution current = queryHandler.execution(session, executionId)
                .orElseThrow(() -> new WorkflowException(
                        "Execution does not exist: " + executionId
                ));
        Map<String, Object> normalizedOutputs = validateResume(
                session,
                current,
                taskRunId,
                outputs
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
     * <p>The result contains the pre-rewind Execution snapshot and the
     * TaskRuns affected by this request in business rollback order. It proves
     * Queue acceptance only; asynchronous command consumption may not have
     * applied the rewind when this method returns.</p>
     *
     * @param session tenant and user context used to validate and enqueue rewind
     * @param executionId Execution that is currently paused
     * @param sourceTaskRunId current Pause TaskRun that requests the rewind
     * @param targetTaskRunId completed historical TaskRun to restart from
     * @param reason non-blank business reason recorded by the rewind command
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
        RewindPlan plan = planRewind(
                session,
                executionId,
                sourceTaskRunId,
                targetTaskRunId
        );
        executorCommandQueue.emit(Rewind.from(
                session,
                executionId,
                sourceTaskRunId,
                targetTaskRunId,
                reason
        ));
        return RewindResult.from(
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
     * @param session tenant and user context used to read the Flow state
     * @param executionId Execution that is currently paused
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

    private <S extends Session<U>, U extends User>
    Map<String, Object> validateResume(
            S session,
            Execution execution,
            String taskRunId,
            Map<String, ?> outputs
    ) {
        if (!execution.state().is(State.Type.PAUSED)) {
            throw new WorkflowException(
                    "Only a PAUSED Execution can resume a Pause TaskRun: "
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
        if (!taskRun.state().is(State.Type.PAUSED)) {
            throw new WorkflowException(
                    "Only a PAUSED TaskRun can be resumed: " + taskRun.id()
            );
        }
        Task task = flow.findTask(taskRun.taskId()).orElseThrow(() ->
                new WorkflowException(
                        "Task definition does not exist: " + taskRun.taskId()
                )
        );
        if (!(task instanceof Pause pause) || !pause.pausesTaskRun()) {
            throw new WorkflowException(
                    "Only a paused Orchestration TaskRun can be resumed: "
                            + taskRun.id()
            );
        }
        return pause.validateResume(outputs);
    }

    private <S extends Session<U>, U extends User> Flow validateRewind(
            S session,
            Execution execution,
            String sourceTaskRunId,
            String targetTaskRunId
    ) {
        if (!execution.state().is(State.Type.PAUSED)) {
            throw new WorkflowException(
                    "Only a PAUSED Execution can rewind: " + execution.id()
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
        validateRewind(flow, execution, sourceTaskRunId, targetTaskRunId);
        return flow;
    }

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
        if (source.parentId().isPresent() || target.parentId().isPresent()) {
            throw new WorkflowException(
                    "Rewind currently supports top-level serial TaskRuns only"
            );
        }
        int sourceIndex = topLevelIndex(flow, source);
        int targetIndex = topLevelIndex(flow, target);
        if (targetIndex >= sourceIndex) {
            throw new WorkflowException(
                    "Rewind target must precede its source in the Flow"
            );
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

    private static List<String> affectedTaskRunIds(
            Flow flow,
            Execution execution,
            String sourceTaskRunId,
            String targetTaskRunId
    ) {
        TaskRun source = execution.requireTaskRun(sourceTaskRunId);
        TaskRun target = execution.requireTaskRun(targetTaskRunId);
        int sourceIndex = topLevelIndex(flow, source);
        int targetIndex = topLevelIndex(flow, target);
        Set<String> affectedTopLevelTaskIds = flow.tasks().subList(
                        targetIndex,
                        sourceIndex + 1
                ).stream()
                .map(Task::id)
                .collect(Collectors.toCollection(HashSet::new));
        Map<String, TaskRun> taskRunsById = execution.taskRuns().stream()
                .collect(Collectors.toMap(
                        TaskRun::id,
                        taskRun -> taskRun,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        List<TaskRun> effectiveTaskRuns = new ArrayList<>(
                execution.effectiveTaskRuns()
        );
        Collections.reverse(effectiveTaskRuns);
        return effectiveTaskRuns.stream()
                .filter(taskRun -> affectedTopLevelTaskIds.contains(
                        topLevelTaskRun(taskRun, taskRunsById).taskId()
                ))
                .map(TaskRun::id)
                .toList();
    }

    private static TaskRun topLevelTaskRun(
            TaskRun taskRun,
            Map<String, TaskRun> taskRunsById
    ) {
        TaskRun current = taskRun;
        Set<String> visited = new HashSet<>();
        while (current.parentId().isPresent()) {
            if (!visited.add(current.id())) {
                throw new IllegalStateException(
                        "TaskRun parent chain contains a cycle: "
                                + taskRun.id()
                );
            }
            String parentId = current.parentId().orElseThrow();
            current = Optional.ofNullable(taskRunsById.get(parentId))
                    .orElseThrow(() -> new IllegalStateException(
                            "TaskRun parent does not exist: " + parentId
                    ));
        }
        return current;
    }

    private static int topLevelIndex(Flow flow, TaskRun taskRun) {
        for (int index = 0; index < flow.tasks().size(); index++) {
            if (flow.tasks().get(index).identifiedBy(taskRun.taskId())) {
                return index;
            }
        }
        throw new WorkflowException(
                "Rewind TaskRun is not a top-level Flow Task: " + taskRun.id()
        );
    }

}
