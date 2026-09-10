package org.cses.flow.executor.handlers;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.executor.ExecutorContext;
import org.cses.flow.executor.ExecutorEvent;
import org.cses.flow.executor.ExecutorEventHandler;
import org.cses.flow.executor.ExecutorService;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.cses.flow.queues.Queue;
import org.cses.flow.worker.WorkerDispatcher;
import org.cses.flow.worker.WorkerTask;
import org.cses.flow.worker.WorkerTaskResult;
import org.jooq.DSLContext;
import org.paas.session.Session;
import org.paas.session.SessionFactory;
import org.paas.session.User;
import org.x9.jooq.JOOQ;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Consumes durable internal Executor events.
 *
 * <p>One delivery owns one scheduling cycle. Once the cycle has persisted its
 * changes, a further runnable cycle is represented by another
 * {@link ExecutorEvent}; no mutable {@link ExecutorContext} is put on the
 * Queue or retained between deliveries.</p>
 */
@Singleton
public class ExecutorEventMessageHandler implements
        ExecutorEventHandler<ExecutorEvent> {

    private JOOQ jooq;
    private SessionFactory<?, ?> sessionFactory;
    private FlowRepository flowRepository;
    private ExecutionRepository executionRepository;
    private ExecutorService executorService;
    private WorkerDispatcher workerDispatcher;
    private Queue<ExecutorEvent> eventQueue;
    private SubFlowExecutionHandler subFlows;

    /**
     * 注入单次调度、子调用及保存后发布事件的运行设施。
     * @param jooq 具名 Flow 数据库访问
     * @param sessionFactory 恢复可信租户和操作者
     * @param flowRepository 精确流程定义仓储
     * @param executionRepository 完整运行仓储
     * @param executorService 领域调度状态机
     * @param workerDispatcher 可执行任务调用器
     * @param eventQueue 内部调度事件发布器
     * @param subFlows 子调用创建与完成协调器
     */
    @Inject
    public ExecutorEventMessageHandler(
            @Named(FlowDatabase.DATA_SOURCE_NAME) JOOQ jooq,
            SessionFactory<?, ?> sessionFactory,
            FlowRepository flowRepository,
            ExecutionRepository executionRepository,
            ExecutorService executorService,
            WorkerDispatcher workerDispatcher,
            Queue<ExecutorEvent> eventQueue,
            SubFlowExecutionHandler subFlows
    ) {
        this.jooq = Objects.requireNonNull(jooq, "jooq");
        this.sessionFactory = Objects.requireNonNull(
                sessionFactory,
                "sessionFactory"
        );
        this.flowRepository = Objects.requireNonNull(
                flowRepository,
                "flowRepository"
        );
        this.executionRepository = Objects.requireNonNull(
                executionRepository,
                "executionRepository"
        );
        this.executorService = Objects.requireNonNull(
                executorService,
                "executorService"
        );
        this.workerDispatcher = Objects.requireNonNull(
                workerDispatcher,
                "workerDispatcher"
        );
        this.eventQueue = Objects.requireNonNull(eventQueue, "eventQueue");
        this.subFlows = Objects.requireNonNull(subFlows, "subFlows");
    }

    /**
     * Constructor for state-machine tests that exercise the handler's local
     * cycle without starting a database-backed Queue consumer.
     */
    public ExecutorEventMessageHandler(
            ExecutionRepository executionRepository,
            ExecutorService executorService,
            WorkerDispatcher workerDispatcher
    ) {
        this.jooq = null;
        this.sessionFactory = null;
        this.flowRepository = null;
        this.executionRepository = Objects.requireNonNull(
                executionRepository,
                "executionRepository"
        );
        this.executorService = Objects.requireNonNull(
                executorService,
                "executorService"
        );
        this.workerDispatcher = Objects.requireNonNull(
                workerDispatcher,
                "workerDispatcher"
        );
        this.eventQueue = null;
    }

    /**
     * 处理一次调度投递，不创建 CAS 作用域或跨 Worker 回调的业务事务。
     * @param event 持久化调度身份
     * @return 运行存在时的当前执行上下文
     * @throws RuntimeException 加载、调度或持久化失败时抛出
     */
    @Override
    public Optional<ExecutorContext> handle(ExecutorEvent event) {
        ExecutorEvent accepted = Objects.requireNonNull(
                event,
                "event"
        );
        requireProductionRuntime();
        return Optional.ofNullable(process(jooq.createDSLContext(), accepted));
    }

    /**
     * Runs an execution directly to a stable boundary. This is retained as a
     * local operation for the Core cancel command and state-machine tests;
     * durable Create/Resume delivery enters through {@link #handle}.
     */
    public <S extends Session<U>, U extends User> Execution execute(
            S session,
            DSLContext dsl,
            ExecutorContext context
    ) {
        requireRuntime(session, dsl, context);
        return drive(session, dsl, context, true);
    }

    public <S extends Session<U>, U extends User> Execution resume(
            S session,
            DSLContext dsl,
            ExecutorContext context,
            String taskRunId,
            Map<String, ?> outputs
    ) {
        requireRuntime(session, dsl, context);
        executorService.resume(context, taskRunId, outputs);
        return drive(session, dsl, context, true);
    }

    public <S extends Session<U>, U extends User> Execution cancel(
            S session,
            DSLContext dsl,
            ExecutorContext context
    ) {
        requireRuntime(session, dsl, context);
        executorService.kill(context);
        return drive(session, dsl, context, false);
    }

    /**
     * 读取运行并在可信会话内处理一次事件，子终态保存后通知父调用。
     * @param dsl 当前数据库上下文
     * @param event 带租户与运行身份的调度事件
     * @return 最新处理上下文
     * @throws RuntimeException 定义缺失、调度或持久化失败时抛出
     */
    private ExecutorContext process(
            DSLContext dsl,
            ExecutorEvent event
    ) {
        Execution execution = executionRepository.findById(
                dsl,
                event.companyId(),
                event.executionId()
        ).orElseThrow(() -> new WorkflowException(
                "Execution does not exist: " + event.executionId()
        ));
        Flow flow = flowRepository.findByFlowId(
            dsl,
            FlowId.from(
                execution.companyId(),
                execution.flowKey(),
                execution.flowVersion()
            )
        ).orElseThrow(() -> new WorkflowException(
            "Flow version does not exist: "
                + execution.flowKey() + ":" + execution.flowVersion()
        ));
        Session<?> session = restoreSession(flow);
        AtomicReference<ExecutorContext> processed =
                new AtomicReference<>();
        inEventScope(
                dsl,
                session,
                () -> processed.set(
                        process(session, dsl, flow, execution, event)
                )
        );
        ExecutorContext result = processed.get();
        subFlows.complete(dsl, result);
        return result;
    }

    /**
     * 保存调度计划，再重读快照启动子调用或 Worker 并合并结果。
     * @param session 已恢复的可信执行会话
     * @param dsl 当前数据库上下文
     * @param flow 绑定的不可变流程定义
     * @param execution 已加载的完整运行
     * @param event 当前调度事件
     * @return 最新处理上下文
     * @throws RuntimeException 调度、任务分派或持久化失败时抛出
     */
    private ExecutorContext process(
            Session<?> session,
            DSLContext dsl,
            Flow flow,
            Execution execution,
            ExecutorEvent event
    ) {
        ExecutorContext context = new ExecutorContext(flow, execution);

        if (!applyEvent(context, event)) {
            return context;
        }

        boolean executionUpdated = false;
        executorService.process(context);
        executionUpdated |= persistIfUpdated(dsl, context);

        List<WorkerTask> workerTasks = context.takeWorkerTasks();
        for (String taskRunId : context.takeSubFlows()) {
            context = subFlows.start(dsl, session, context, taskRunId);
            executionUpdated = true;
            if (context.execution().isTerminal()) return context;
        }

        if (workerTasks.isEmpty()) {
            if (executionUpdated && context.canBeProcessed()) {
                emitNext(event);
            }
            return context;
        }

        for (WorkerTask workerTask : workerTasks) {
            context = reload(dsl, flow, event);
            if (context.execution().isTerminal() || context.execution().state().is(State.Type.KILLING)) {
                return context;
            }
            if (!context.execution().requireTaskRun(workerTask.taskRunId()).state().is(State.Type.CREATED)) {
                continue;
            }
            workerTask = executorService.dispatch(context, workerTask);
            persistIfUpdated(dsl, context);
            WorkerTaskResult result = dispatchWorkerTask(
                    workerTask, event.eventType() != ExecutorEvent.EventType.TERMINATED);
            context = applyResult(dsl, flow, event, result);
            executionUpdated = true;
            if (result.targetState() == State.Type.FAILED || result.targetState() == State.Type.KILLED) {
                return context;
            }
        }

        if (executionUpdated && context.canBeProcessed()) {
            emitNext(event);
        }
        return context;
    }

    /**
     * Reloads the complete aggregate before applying a worker result or claiming another task.
     * @param dsl database context
     * @param flow bound immutable definition
     * @param event current scheduling identity
     * @return context containing the latest persisted execution
     */
    private ExecutorContext reload(DSLContext dsl, Flow flow, ExecutorEvent event) {
        Execution execution = executionRepository.findById(dsl, event.companyId(), event.executionId())
                .orElseThrow(() -> new WorkflowException("Execution does not exist: " + event.executionId()));
        return new ExecutorContext(flow, execution);
    }

    /**
     * Merges one completed worker result into the latest aggregate without rerunning the worker.
     * @param dsl database context
     * @param flow bound immutable definition
     * @param event scheduling identity
     * @param result completed worker output
     * @return updated or already terminated execution context
     * @throws RuntimeException when persistence fails for a reason other than a stale snapshot
     */
    private ExecutorContext applyResult(DSLContext dsl, Flow flow, ExecutorEvent event, WorkerTaskResult result) {
        while (true) {
            ExecutorContext current = reload(dsl, flow, event);
            if (current.execution().isTerminal() || current.execution().state().is(State.Type.KILLING)
                    || !current.execution().requireTaskRun(result.taskRunId()).state().is(State.Type.RUNNING)) {
                return current;
            }
            executorService.applyResult(current, result);
            try {
                persistIfUpdated(dsl, current);
                return current;
            } catch (org.jooq.exception.DataChangedException conflict) {
                // Preserve this completed output; retry its domain application without rerunning the Worker.
            }
        }
    }

    private boolean applyEvent(
            ExecutorContext context,
            ExecutorEvent event
    ) {
        return switch (event.eventType()) {
            case CREATED, UPDATED -> true;
            case TERMINATED -> {
                if (context.execution().isTerminal()) {
                    yield false;
                }
                if (!context.execution().state().is(State.Type.KILLING)) {
                    executorService.kill(context);
                }
                yield true;
            }
        };
    }

    /**
     * Publishes the next scheduling cycle after domain persistence has completed.
     * @param event current durable scheduling identity
     */
    private void emitNext(ExecutorEvent event) {
        eventQueue.emit(event.nextUpdate());
    }

    private boolean persistIfUpdated(
            DSLContext dsl,
            ExecutorContext context
    ) {
        if (!context.takeExecutionUpdated()) {
            return false;
        }
        executionRepository.save(dsl, context.execution());
        return true;
    }

    /**
     * 本地推进至稳定边界，子调用仍通过生产队列运行。
     * @param <S> 可信会话类型
     * @param <U> 会话用户类型
     * @param session 当前可信会话
     * @param dsl 数据库上下文
     * @param context 待推进的运行快照，会被修改
     * @param captureUnexpectedTaskFailure 是否把 Worker 异常收敛为任务失败
     * @return 稳定边界的运行副本
     * @throws RuntimeException 缺少运行设施或持久化失败时抛出
     */
    private <S extends Session<U>, U extends User> Execution drive(
            S session,
            DSLContext dsl,
            ExecutorContext context,
            boolean captureUnexpectedTaskFailure
    ) {
        while (true) {
            executorService.process(context);
            List<WorkerTask> workerTasks = context.takeWorkerTasks();
            boolean executionUpdated = context.takeExecutionUpdated();
            if (executionUpdated) {
                persist(dsl, context);
            }

            for (String taskRunId : context.takeSubFlows()) {
                if (subFlows == null) throw new IllegalStateException("SubFlow requires the production runtime");
                context = subFlows.start(dsl, session, context, taskRunId);
            }

            if (workerTasks.isEmpty()) {
                if (executionUpdated && context.canBeProcessed()) {
                    continue;
                }
                return context.execution().copy();
            }

            for (WorkerTask workerTask : workerTasks) {
                workerTask = executorService.dispatch(context, workerTask);
                if (context.takeExecutionUpdated()) {
                    persist(dsl, context);
                }

                WorkerTaskResult result = dispatchWorkerTask(
                        workerTask,
                        captureUnexpectedTaskFailure
                );
                executorService.applyResult(context, result);
                if (result.targetState() == State.Type.FAILED
                        || result.targetState() == State.Type.KILLED) {
                    if (context.takeExecutionUpdated()) {
                        persist(dsl, context);
                    }
                    return context.execution().copy();
                }
            }
        }
    }

    private WorkerTaskResult dispatchWorkerTask(
            WorkerTask workerTask,
            boolean captureUnexpectedTaskFailure
    ) {
        if (!captureUnexpectedTaskFailure) {
            return workerDispatcher.dispatch(workerTask);
        }
        try {
            return workerDispatcher.dispatch(workerTask);
        } catch (RuntimeException exception) {
            return WorkerTaskResult.failed(
                    workerTask,
                    unexpectedFailure(exception)
            );
        }
    }

    private static String unexpectedFailure(RuntimeException exception) {
        String detail = exception.getMessage();
        String type = exception.getClass().getSimpleName();
        if (detail == null || detail.isBlank()) {
            return "RunnableTask failed unexpectedly: " + type;
        }
        return "RunnableTask failed unexpectedly: " + type + ": " + detail;
    }

    private void persist(DSLContext dsl, ExecutorContext context) {
        executionRepository.save(dsl, context.execution());
    }

    private Session<?> restoreSession(Flow flow) {
        @SuppressWarnings({"rawtypes", "unchecked"})
        Session restored = sessionFactory.session();
        User user = restoreUser(flow.creator().id());
        user.setId(flow.creator().id());
        user.setCompanyId(flow.companyId());
        flow.creator().name().ifPresent(name -> {
            user.setName(name);
            user.setUserName(name);
        });
        restored.setCompanyId(flow.companyId());
        restored.setUser(user);
        user.onSessionBound();
        return restored;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private User restoreUser(String actorId) {
        List<? extends User> users = ((SessionFactory) sessionFactory)
                .buildSessionUser(List.of(actorId));
        if (users != null && !users.isEmpty()) {
            return users.getFirst();
        }
        return sessionFactory.user();
    }

    private void inEventScope(
            DSLContext dsl,
            Session<?> session,
            Runnable action
    ) {
        Object previousSession = dsl.configuration().data(Session.class);
        dsl.configuration().data(Session.class, session);
        try {
            action.run();
        } finally {
            if (previousSession == null) {
                dsl.configuration().data().remove(Session.class);
            } else {
                dsl.configuration().data(Session.class, previousSession);
            }
        }
    }

    private void requireProductionRuntime() {
        if (jooq == null || sessionFactory == null || flowRepository == null
                || eventQueue == null) {
            throw new IllegalStateException(
                    "Executor event handling requires the production runtime"
            );
        }
    }

    private static <S extends Session<U>, U extends User> void requireRuntime(
            S session,
            DSLContext dsl,
            ExecutorContext context
    ) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(dsl, "dsl");
        Objects.requireNonNull(context, "context");
    }
}
