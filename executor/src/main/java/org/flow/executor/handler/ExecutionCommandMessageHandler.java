package org.flow.executor.handler;

import java.util.Optional;
import java.util.Objects;

import org.flow.core.async.AsyncOperationProcessedEvent;
import org.flow.core.async.AsyncOperationService;
import org.flow.core.exceptions.FlowNotFoundException;
import org.flow.core.exceptions.InternalException;
import org.flow.core.executor.command.*;
import org.flow.core.killswitch.EvaluationType;
import org.flow.core.killswitch.KillSwitchService;
import org.flow.core.models.Label;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.flows.FlowWithSource;
import org.flow.core.models.flows.State;
import org.flow.core.runners.ExecutionEvent;
import org.flow.core.runners.ExecutionEventType;
import org.flow.core.runners.FlowMetaStoreInterface;
import org.flow.core.runners.ProcessedFlow;
import org.flow.core.services.ExecutionOutputService;
import org.flow.core.services.ExecutionService;
import org.flow.core.services.TaskOutputService;
import org.flow.core.utils.ListUtils;
import org.flow.executor.ExecutionStateStore;
import org.flow.executor.ExecutorContext;
import org.flow.executor.ExecutorMessageHandler;
import org.flow.executor.KillSwitchActionService;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class ExecutionCommandMessageHandler implements ExecutorMessageHandler<ExecutionCommand> {
    private final ExecutionService executionService;
    private final ExecutionStateStore executionStateStore;
    private final FlowMetaStoreInterface flowMetaStore;
    private final TaskOutputService taskOutputService;
    private final ExecutionOutputService executionOutputService;
    private final AsyncOperationService asyncOperationService;
    private final ExecutionEventMessageHandler executionEventMessageHandler;
    private final KillSwitchService killSwitchService;
    private final KillSwitchActionService killSwitchActionService;

    /**
     * 注入处理执行命令所需的运行组件。构造时只保存依赖引用。
     * @param executionService 非空的执行创建及状态变更服务
     * @param executionStateStore 非空的执行状态存储，用于创建、读取和锁定执行
     * @param flowMetaStore 非空的运行定义存储，用于加载命令对应的流程版本
     * @param taskOutputService 非空的任务输出服务，用于迁移历史输出
     * @param executionOutputService 非空的执行输出服务，用于迁移历史输出
     * @param asyncOperationService 非空的异步操作服务，用于发布命令处理结果
     * @param executionEventMessageHandler 非空的执行事件处理器，用于推进新执行
     * @param killSwitchService 非空的停止策略查询服务
     * @param killSwitchActionService 非空的停止策略执行服务
     */
    @Inject
    public ExecutionCommandMessageHandler(
        ExecutionService executionService,
        ExecutionStateStore executionStateStore,
        FlowMetaStoreInterface flowMetaStore,
        TaskOutputService taskOutputService,
        ExecutionOutputService executionOutputService,
        AsyncOperationService asyncOperationService,
        ExecutionEventMessageHandler executionEventMessageHandler,
        KillSwitchService killSwitchService,
        KillSwitchActionService killSwitchActionService) {
        this.executionService = executionService;
        this.executionStateStore = executionStateStore;
        this.flowMetaStore = flowMetaStore;
        this.taskOutputService = taskOutputService;
        this.executionOutputService = executionOutputService;
        this.asyncOperationService = asyncOperationService;
        this.executionEventMessageHandler = executionEventMessageHandler;
        this.killSwitchService = killSwitchService;
        this.killSwitchActionService = killSwitchActionService;
    }

    /**
     * 消费原生命令或携带操作者标识的命令。带操作者的命令使用消息中的身份记录日志，
     * 并按原生命令协议检查目标和更新状态。
     * @param message 非空的执行命令，包含目标以及该操作所需的参数
     * @return 推进后的执行上下文；没有发生推进或处理失败时返回空 Optional
     */
    @Override
    public Optional<ExecutorContext> handle(ExecutionCommand message) {
        if (message instanceof SessionCommand scoped) {
            log.info("Processing execution command {} for tenant {} by actor {}, eventId {}",
                scoped.command().type(), scoped.tenantId(), scoped.actorId(), scoped.eventId());
            return handle(scoped.command());
        }
        // Create and Replay bootstrap a new execution — they cannot go through executionStateStore.lock()
        // because the execution doesn't exist yet. All other commands mutate an existing execution via lock.
        if (message instanceof Create createCommand) {
            return handleCreate(createCommand);
        }
        if (message instanceof Replay replayCommand) {
            return handleReplay(replayCommand);
        }

        // For existing executions, check kill switch before taking the lock.
        EvaluationType evaluationType = killSwitchService.evaluate(message);
        if (evaluationType != EvaluationType.PASS) {
            var execution = executionStateStore.findByIdWithoutAcl(message.executionId());
            if (execution != null && matchesTarget(message, execution) && evaluationType.isKillSwitched(execution)) {
                killSwitchActionService.handle(evaluationType, execution.getTenantId(), execution.getId());
                return Optional.empty();
            }
        }

        return executionStateStore.lock(message.executionId(), execution ->
        {
            AsyncOperationProcessedEvent.Outcome outcome = AsyncOperationProcessedEvent.Outcome.SUCCEEDED;
            String error = null;
            try {
                requireTarget(message, execution);
                var flow = flowMetaStore.findByExecutionForRuntime(execution).orElseThrow(() -> new FlowNotFoundException(execution));
                var executorContext = new ExecutorContext(execution, flow);
                var newExecution = switch (message) {
                    case Restart restartCommand ->
                        executionService.restart(execution, executorContext.getFlow(), restartCommand.revision(), true);
                    case Pause ignored ->
                        executionService.pause(execution);
                    case Unqueue unqueueCommand ->
                        executionService.unqueue(execution, unqueueCommand.state());
                    case ForceRun ignored ->
                        executionService.forceRun(execution, executorContext.getFlow());
                    case ChangeTaskRunState changeTaskRunStateCommand ->
                        executionService.changeTaskRunState(execution, flow, changeTaskRunStateCommand.taskRunId(), changeTaskRunStateCommand.state());
                    case UpdateLabels updateLabels ->
                        executionService.updateLabels(execution, updateLabels.labels());
                    case UpdateStatus updateStatusCommand ->
                        executionService.changeState(execution, updateStatusCommand.state());
                    case ResumeFromBreakpoint resumeFromBreakpointCommand ->
                        executionService.resumeFromBreakpoint(execution, resumeFromBreakpointCommand.breakpoints());
                    case Resume resumeCommand ->
                        executionService.resume(execution, flow, State.Type.RUNNING, resumeCommand.resumeInputs(), resumeCommand.resumed());
                    case ExecutionCommand.Invalid ignored -> {
                        log.error("Invalid command for execution {}: ignoring command with eventId {}", message.executionId(), message.eventId());
                        yield null;
                    }
                    default -> throw new IllegalStateException("Unexpected value: " + message); // should never happen, would be a bug
                };
                return newExecution != null ? executorContext.withExecution(migrateOutputs(newExecution), "ExecutionCommandMessageHandler") : null;
            } catch (Exception e) {
                log.error("Unable to process event for execution {}: ignoring {} command with eventId {}", message.executionId(), message.getClass().getSimpleName(), message.eventId(), e);
                outcome = AsyncOperationProcessedEvent.Outcome.FAILED;
                error = e.getMessage();
                return null;
            } finally {
                asyncOperationService.emitProcessedIfAsync(message, message.tenantId(), message.executionId(), outcome, error);
            }
        });
    }

    /** 验证租户和流程一致，禁止仅凭执行 ID 修改其他租户状态。 */
    private boolean matchesTarget(ExecutionCommand command, Execution execution) {
        return Objects.equals(command.tenantId(), execution.getTenantId())
            && Objects.equals(command.namespace(), execution.getNamespace())
            && Objects.equals(command.flowId(), execution.getFlowId());
    }

    /** 在读取重放来源或持有执行锁时再次校验命令目标。 */
    private void requireTarget(ExecutionCommand command, Execution execution) {
        if (execution == null || !matchesTarget(command, execution)) {
            throw new IllegalArgumentException("命令目标不属于指定租户和流程");
        }
    }

    private Optional<ExecutorContext> handleCreate(Create command) {
        AsyncOperationProcessedEvent.Outcome outcome = AsyncOperationProcessedEvent.Outcome.SUCCEEDED;
        String error = null;
        try {
            var processedFlow = flowMetaStore
                .findByIdForRuntime(command.tenantId(), command.namespace(), command.flowId(), Optional.ofNullable(command.flowRevision()))
                .orElseThrow(() -> new FlowNotFoundException(command.executionFullId(), command.flowRevision()));

            var newExecution = executionService.create(command, processedFlow);

            var persisted = persistNewExecutionWithKillSwitch(newExecution);
            if (persisted.isEmpty()) {
                return Optional.empty();
            }
            newExecution = persisted.get();

            // A terminal execution (e.g. a trigger that failed to render its inputs) needs no
            // further processing by the executor — it is already in its final state.
            if (newExecution.getState().isTerminated()) {
                return Optional.of(new ExecutorContext(newExecution)); // short-circuit execution processing
            }

            var eventType = newExecution.getState().isCreated() ? ExecutionEventType.CREATED : ExecutionEventType.UPDATED;
            return executionEventMessageHandler.handle(new ExecutionEvent(newExecution, eventType));
        } catch (Exception e) {
            log.error(
                "Unable to process Create command for execution {}: ignoring command with eventId {}",
                command.executionId(), command.eventId(), e
            );
            outcome = AsyncOperationProcessedEvent.Outcome.FAILED;
            error = e.getMessage();
            return Optional.empty();
        } finally {
            asyncOperationService.emitProcessedIfAsync(command, command.tenantId(), command.executionId(), outcome, error);
        }
    }

    private Optional<ExecutorContext> handleReplay(Replay command) {
        AsyncOperationProcessedEvent.Outcome outcome = AsyncOperationProcessedEvent.Outcome.SUCCEEDED;
        String error = null;
        try {
            var raw = executionStateStore.findByIdWithoutAcl(command.sourceExecutionId());
            if (raw == null) {
                throw new IllegalStateException("Source execution not found: " + command.sourceExecutionId());
            }

            requireTarget(command, raw);

            // Apply inputs override if the controller merged new inputs before emitting the command
            final var sourceExecution = command.inputs() != null ? raw.withInputs(command.inputs()) : raw;

            FlowWithSource flow;
            if (command.revision() != null) {
                flow = flowMetaStore
                    .findByIdForRuntime(command.tenantId(), command.namespace(), command.flowId(), Optional.of(command.revision()))
                    .map(ProcessedFlow::flow)
                    .orElseThrow(() -> new FlowNotFoundException(sourceExecution));
            } else {
                flow = flowMetaStore
                    .findByExecutionForRuntime(sourceExecution)
                    .orElseThrow(() -> new FlowNotFoundException(sourceExecution));
            }

            var newExecution = executionService.replay(
                sourceExecution,
                flow,
                command.taskRunId(),
                command.revision(),
                Optional.ofNullable(command.breakpoints()),
                true,
                command.executionId()
            );

            var persisted = persistNewExecutionWithKillSwitch(newExecution);
            if (persisted.isEmpty()) {
                return Optional.empty();
            }
            newExecution = persisted.get();

            // A terminal execution (e.g. killed or cancelled by the kill switch) needs no further
            // processing by the executor — it is already in its final state.
            if (newExecution.getState().isTerminated()) {
                return Optional.of(new ExecutorContext(newExecution)); // short-circuit execution processing
            }

            return executionEventMessageHandler.handle(new ExecutionEvent(newExecution, ExecutionEventType.CREATED));
        } catch (Exception e) {
            log.error(
                "Unable to process Replay command for new execution {}: ignoring command with eventId {}",
                command.executionId(), command.eventId(), e
            );
            outcome = AsyncOperationProcessedEvent.Outcome.FAILED;
            error = e.getMessage();
            return Optional.empty();
        } finally {
            asyncOperationService.emitProcessedIfAsync(command, command.tenantId(), command.executionId(), outcome, error);
        }
    }

    /**
     * Evaluates the kill switch on a brand-new (not yet persisted) execution and writes it to the state
     * store in the appropriate terminal state if kill-switched.
     *
     * <p>
     * A brand-new execution has no running tasks, so it can be written directly in a terminal state
     * without going through the KILLING intermediate state or the kill queue.
     * </p>
     *
     * @return {@code Optional.of(execution)} if the execution should proceed to normal processing;
     *         {@code Optional.empty()} if it was kill-switched IGNORE.
     */
    private Optional<Execution> persistNewExecutionWithKillSwitch(Execution execution) {
        EvaluationType evaluationType = killSwitchService.evaluate(execution);
        if (evaluationType == EvaluationType.IGNORE) {
            log.warn("Kill switch active (IGNORE): ignoring execution {}", execution.getId());
            executionStateStore.create(execution.addLabel(new Label(Label.KILL_SWITCH, "ignored")));
            return Optional.empty(); // short-circuit execution processing
        }

        // KILL and CANCEL should still reach toExecution so terminal steps are done
        Execution newExecution;
        if (evaluationType == EvaluationType.KILL) {
            log.warn("Kill switch active (KILL): killing execution {}", execution.getId());
            newExecution = execution.withState(State.Type.KILLED).addLabel(new Label(Label.KILL_SWITCH, "killed"));
        }
        else if (evaluationType == EvaluationType.CANCEL) {
            log.warn("Kill switch active (CANCEL): cancelling execution {}", execution.getId());
            newExecution = execution.withState(State.Type.CANCELLED).addLabel(new Label(Label.KILL_SWITCH, "cancelled"));
        } else {
            newExecution = execution;
        }

        executionStateStore.create(newExecution);
        return Optional.of(newExecution);
    }

    /**
     * Pre-2.0 backward compatibility: if the execution or one of its task runs carries inline outputs (deprecated
     * {@code outputs} fields), persist them into the output repositories so the rest of the executor can work
     * uniformly with the modern storage.
     */
    @SuppressWarnings("deprecation")
    private Execution migrateOutputs(Execution execution) throws InternalException {
        if (execution.getOutputs() != null) {
            executionOutputService.saveOutputs(execution, execution.getOutputs());
            execution = execution.withOutputs(null);
        }

        if (ListUtils.isEmpty(execution.getTaskRunList())) {
            return execution;
        }

        for (TaskRun taskRun : execution.getTaskRunList()) {
            if (taskRun.getOutputs() != null) {
                taskOutputService.saveOutputs(taskRun, taskRun.getOutputs());
                execution = execution.withTaskRun(taskRun.clearOutputs());
            }
        }
        return execution;
    }
}
