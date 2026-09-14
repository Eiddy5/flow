package org.cses.flow.executor.handlers;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.services.CommandContext;
import org.cses.flow.core.services.executions.ExecutionService;
import org.cses.flow.executor.ExecutorContext;
import org.cses.flow.executor.ExecutorEvent;
import org.cses.flow.executor.ExecutorEventHandler;
import org.cses.flow.executor.commands.*;
import org.cses.flow.extensions.flow.Pause;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.cses.flow.queues.Queue;
import org.jooq.DSLContext;
import org.paas.session.Session;
import org.paas.session.SessionFactory;
import org.paas.session.User;
import org.x9.jooq.JOOQ;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The only external command entry into the Executor.
 *
 * <p>This handler validates command facts, materializes or updates the durable
 * Execution as necessary, and then publishes an internal
 * {@link ExecutorEvent}. It does not create an {@link ExecutorContext} or
 * drive the scheduling cycle.</p>
 */
@Singleton
public class ExecutionCommandEventHandler implements
        ExecutorEventHandler<ExecutionCommand> {

    private JOOQ jooq;
    private SessionFactory<?, ?> sessionFactory;
    private FlowRepository flowRepository;
    private ExecutionRepository executionRepository;
    private Queue<ExecutorEvent> eventQueue;

    /**
     * Wires command persistence and subsequent transport publication without a shared transaction.
     * @param jooq named Flow database access
     * @param sessionFactory restores the command tenant and actor
     * @param flowRepository reads the exact flow definition
     * @param executionRepository reads and saves complete execution snapshots
     * @param eventQueue annotation-selected internal event publisher
     */
    @Inject
    public ExecutionCommandEventHandler(
            @Named(FlowDatabase.DATA_SOURCE_NAME) JOOQ jooq,
            SessionFactory<?, ?> sessionFactory,
            FlowRepository flowRepository,
            ExecutionRepository executionRepository,
            Queue<ExecutorEvent> eventQueue
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
        this.eventQueue = Objects.requireNonNull(eventQueue, "eventQueue");
    }

    /**
     * 对完整快照应用命令，保存后发送调度信号；不创建 CAS 作用域或业务事务。
     * 重投时也为已保存的非终态运行补发信号，以处理上次发布失败的情况。
     * @param command 已受理的外部运行命令
     * @return 空结果，本处理器不执行调度周期
     * @throws RuntimeException 命令校验、持久化或发布失败时抛出
     */
    @Override
    public Optional<ExecutorContext> handle(ExecutionCommand command) {
        ExecutionCommand accepted = Objects.requireNonNull(
                command,
                "command"
        );
        accepted.validate();
        DSLContext dsl = jooq.createDSLContext();
        route(dsl, accepted);
        String scheduledId = accepted instanceof Rewind rewind
                ? rewind.getReplayExecutionId() : accepted.key();
        Optional<Execution> scheduled = executionRepository.findById(dsl, companyId(accepted), scheduledId);
        if (scheduled.isEmpty() && accepted instanceof Rewind) return Optional.empty();
        Execution execution = scheduled.orElseThrow(() ->
                new WorkflowException("Execution does not exist: " + scheduledId));
        if (!execution.isTerminal()) {
            ExecutorEvent.EventType type = switch (execution.state().current()) {
                case CREATED -> ExecutorEvent.EventType.CREATED;
                case KILLING -> ExecutorEvent.EventType.TERMINATED;
                default -> ExecutorEvent.EventType.UPDATED;
            };
            eventQueue.emit(ExecutorEvent.from(execution, type));
        }
        return Optional.empty();
    }

    /**
     * Extracts the tenant carried by each accepted command without widening the public protocol.
     * @param command validated external command
     * @return tenant owning the execution
     */
    private static String companyId(ExecutionCommand command) {
        return switch (command) {
            case Create value -> value.getCompanyId();
            case Resume value -> value.getCompanyId();
            case Cancel value -> value.getCompanyId();
            case Rewind value -> value.getCompanyId();
        };
    }

    private void route(
            DSLContext dsl,
            ExecutionCommand command
    ) {
        switch (command) {
            case Create create -> handleCreate(dsl, create);
            case Resume resume -> {
                Session<?> session = restoreSession(resume);
                inCommandScope(
                        dsl,
                        session,
                        resume,
                        () -> handleResume(dsl, resume)
                );
            }
            case Rewind rewind -> {
                Session<?> session = restoreSession(
                        rewind.getCompanyId(),
                        rewind.getActorId()
                );
                inCommandScope(
                        dsl,
                        session,
                        rewind,
                        () -> handleRewind(dsl, rewind)
                );
            }
            case Cancel cancel -> {
                Session<?> session = restoreSession(cancel);
                inCommandScope(
                        dsl,
                        session,
                        cancel,
                        () -> handleCancel(dsl, cancel)
                );
            }
        }
    }

    /**
     * Applies cancellation to the loaded Execution domain and saves its complete snapshot.
     * @param dsl ordinary database context
     * @param command exact execution cancellation request
     * @throws WorkflowException when the execution does not exist
     */
    private void handleCancel(DSLContext dsl, Cancel command) {
        Execution execution = executionRepository.findById(
                dsl,
                command.getCompanyId(),
                command.getExecutionId()
        ).orElseThrow(() -> new WorkflowException(
                "Execution does not exist: " + command.getExecutionId()
        ));

        if (execution.isTerminal()
                || execution.state().is(State.Type.KILLING)) {
            return;
        }
        execution.beginKilling();
        executionRepository.save(dsl, execution);
    }

    /**
     * Materializes a new execution or verifies the facts of the existing same-ID execution.
     * @param dsl ordinary database context
     * @param command frozen creation identity and inputs
     * @throws WorkflowException when repeated identity conflicts with stored facts
     */
    private void handleCreate(DSLContext dsl, Create command) {
        Session<?> session = restoreSession(
                command.getCompanyId(),
                command.getActorId()
        );
        inCommandScope(
                dsl,
                session,
                command,
                () -> {
                    Optional<Execution> existing = executionRepository.findById(
                            dsl,
                            command.getCompanyId(),
                            command.getExecutionId()
                    );
                    if (existing.isPresent()) {
                        validateRepeatedCreate(existing.orElseThrow(), command);
                        return;
                    }
                    Flow flow = flowRepository.findByFlowId(
                            dsl,
                            FlowId.from(
                                    command.getCompanyId(),
                                    command.getFlowKey(),
                                    command.getFlowVersion()
                            )
                    ).orElseThrow(() -> new WorkflowException(
                            "Flow version does not exist: "
                                    + command.getFlowKey() + ":"
                                    + command.getFlowVersion()
                    ));
                    if (flow.deleted()) {
                        throw new WorkflowException(
                                "Only an undeleted Flow can start an Execution: "
                                        + flow.key() + "@" + flow.reversion()
                        );
                    }
                    Map<String, Object> normalizedInputs = flow.bindInputs(
                            command.getInputs()
                    );
                    Execution execution = Execution.create(
                            command.getExecutionId(),
                            session,
                            flow.key(),
                            flow.reversion(),
                            normalizedInputs
                    );
                    executionRepository.save(dsl, execution);
                }
        );
    }

    private static void validateRepeatedCreate(
            Execution execution,
            Create command
    ) {
        if (!execution.flowKey().equals(command.getFlowKey())
                || execution.flowVersion() != command.getFlowVersion()
                || !execution.inputs().equals(command.getInputs())) {
            throw new WorkflowException(
                    "Execution id already belongs to another start request: "
                            + command.getExecutionId()
            );
        }
    }

    /**
     * Resumes the exact paused domain occurrence while preserving other branch states.
     * @param dsl ordinary database context
     * @param command target occurrence and validated outputs
     * @throws WorkflowException when the bound definition or occurrence is invalid
     */
    private void handleResume(DSLContext dsl, Resume command) {
        Execution execution = executionRepository.findById(
                dsl,
                command.getCompanyId(),
                command.getExecutionId()
        ).orElseThrow(() -> new WorkflowException(
                "Execution does not exist: " + command.getExecutionId()
        ));

        // A duplicate delivery can observe the state written by a previous
        // Resume. Acknowledge it instead of retrying an already stale command.
        if (!execution.canResumeTaskRun()) {
            return;
        }

        Flow flow = flowRepository.findByFlowId(
                dsl,
                FlowId.from(
                        command.getCompanyId(),
                        execution.flowKey(),
                        execution.flowVersion()
                )
        ).orElseThrow(() -> new WorkflowException(
                "Flow version does not exist: "
                        + execution.flowKey() + ":" + execution.flowVersion()
        ));
        TaskRun taskRun = execution.requireTaskRun(command.getTaskRunId());
        if (execution.isAwaitingPause(taskRun.id())) {
            throw new WorkflowException("Resume is waiting for the pre-pause action: " + taskRun.id());
        }
        if (!taskRun.state().is(State.Type.PAUSED)) {
            return;
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
        Map<String, Object> normalizedOutputs = pause.bindResume(
                command.getOutputs()
        );
        execution.resumeTaskRun(taskRun.id(), normalizedOutputs);
        executionRepository.save(dsl, execution);
    }

    /**
     * Creates and atomically saves a derived Execution, or validates an existing result on redelivery.
     * @param dsl ordinary database context
     * @param command rewind occurrence and reason
     * @throws WorkflowException when the rewind cannot be applied
     */
    private void handleRewind(DSLContext dsl, Rewind command) {
        Optional<Execution> existing = executionRepository.findById(
                dsl, command.getCompanyId(), command.getReplayExecutionId());
        if (existing.isPresent()) {
            Execution replayed = existing.orElseThrow();
            if (!command.getExecutionId().equals(replayed.origin().parentId())) {
                throw new WorkflowException("Replay identity is already bound to another source");
            }
            var generation = replayed.generation();
            var replay = generation.current().orElseGet(() -> generation.history().currents().getLast());
            if (!command.getSourceTaskRunId().equals(replay.sourceTaskRunId().orElse(null))
                    || !command.getTargetTaskRunId().equals(replay.targetTaskRunId().orElse(null))
                    || !command.getReason().equals(replay.reason())) {
                throw new WorkflowException("Replay identity is already bound to another request");
            }
            return;
        }
        Execution execution = executionRepository.findById(
                dsl,
                command.getCompanyId(),
                command.getExecutionId()
        ).orElseThrow(() -> new WorkflowException(
                "Execution does not exist: " + command.getExecutionId()
        ));

        if (execution.isTerminal() || execution.state().is(State.Type.KILLING)) {
            return;
        }
        TaskRun source = execution.requireTaskRun(
                command.getSourceTaskRunId()
        );
        if (!source.state().is(State.Type.PAUSED)) {
            return;
        }
        Flow flow = flowRepository.findByFlowId(
                dsl,
                FlowId.from(
                        command.getCompanyId(),
                        execution.flowKey(),
                        execution.flowVersion()
                )
        ).orElseThrow(() -> new WorkflowException(
                "Flow version does not exist: "
                        + execution.flowKey() + ":" + execution.flowVersion()
        ));
        ExecutionService.validateRewind(
                flow,
                execution,
                command.getSourceTaskRunId(),
                command.getTargetTaskRunId()
        );
        if (execution.activeTaskRuns().stream().anyMatch(run ->
                run.state().is(State.Type.RUNNING)
                        && flow.findTask(run.taskId()).orElseThrow() instanceof RunnableTask)) {
            throw new WorkflowException("Replay waits for in-flight Worker results before taking its snapshot");
        }
        Execution replayed = execution.replay(
                command.getReplayExecutionId(),
                restoreSession(command.getCompanyId(), command.getActorId()),
                command.getSourceTaskRunId(),
                command.getTargetTaskRunId(),
                command.getReason(),
                ExecutionService.affectedTaskRunIds(flow, execution, command.getSourceTaskRunId(), command.getTargetTaskRunId())
        );
        executionRepository.save(dsl, execution, replayed);
    }

    private static void inCommandScope(
            DSLContext dsl,
            Session<?> session,
            ExecutionCommand command,
            Runnable action
    ) {
        Object previousSession = dsl.configuration().data(Session.class);
        Object previousContext = dsl.configuration().data(
                CommandContext.class
        );
        dsl.configuration().data(Session.class, session);
        dsl.configuration().data(CommandContext.class, command);
        try {
            action.run();
        } finally {
            restoreScope(dsl, Session.class, previousSession);
            restoreScope(dsl, CommandContext.class, previousContext);
        }
    }

    private static void restoreScope(
            DSLContext dsl,
            Class<?> key,
            Object previous
    ) {
        if (previous == null) {
            dsl.configuration().data().remove(key);
        } else {
            dsl.configuration().data(key, previous);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Session<?> restoreSession(
            String companyId,
            String actorId
    ) {
        Session restored = sessionFactory.session();
        User user = restoreUser(actorId);
        user.setId(actorId);
        user.setCompanyId(companyId);

        restored.setCompanyId(companyId);
        restored.setUser(user);
        user.onSessionBound();
        return restored;
    }

    private Session<?> restoreSession(Resume command) {
        return restoreSession(command.getCompanyId(), command.getActorId());
    }

    private Session<?> restoreSession(Cancel command) {
        return restoreSession(command.getCompanyId(), command.getActorId());
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
}
