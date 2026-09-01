package org.cses.flow.executor.handlers;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.cses.flow.core.services.CommandContext;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.services.executions.ExecutionService;
import org.cses.flow.executor.ExecutorContext;
import org.cses.flow.executor.ExecutorEvent;
import org.cses.flow.executor.commands.Cancel;
import org.cses.flow.executor.commands.Create;
import org.cses.flow.executor.commands.ExecutionCommand;
import org.cses.flow.executor.commands.Resume;
import org.cses.flow.executor.commands.Rewind;
import org.cses.flow.extensions.flow.Pause;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.cses.flow.queues.DispatchQueue;
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
 * Execution as necessary, and atomically publishes an internal
 * {@link ExecutorEvent}. It does not create an {@link ExecutorContext} or
 * drive the scheduling cycle.</p>
 */
@Singleton
public class ExecutionCommandEventHandler implements
    org.cses.flow.executor.ExecutorEventHandler<ExecutionCommand> {

    private JOOQ jooq;
    private SessionFactory<?, ?> sessionFactory;
    private FlowRepository flowRepository;
    private ExecutionRepository executionRepository;
    private DispatchQueue<ExecutorEvent> eventQueue;

    @Inject
    public ExecutionCommandEventHandler(
        @Named(FlowDatabase.DATA_SOURCE_NAME) JOOQ jooq,
        SessionFactory<?, ?> sessionFactory,
        FlowRepository flowRepository,
        ExecutionRepository executionRepository,
        @Named(ExecutorEvent.QUEUE_NAME)
        DispatchQueue<ExecutorEvent> eventQueue
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

    @Override
    public Optional<ExecutorContext> handle(ExecutionCommand command) {
        ExecutionCommand accepted = Objects.requireNonNull(
            command,
            "command"
        );
        accepted.validate();
        jooq.run(dsl -> route(dsl, accepted));
        return Optional.empty();
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
        eventQueue.emitInTransaction(
            ExecutorEvent.from(
                execution,
                ExecutorEvent.EventType.TERMINATED
            ),
            dsl
        );
    }

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
                Map<String, Object> normalizedInputs = flow.normalizeInputs(
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
                eventQueue.emitInTransaction(
                    ExecutorEvent.from(
                        execution,
                        ExecutorEvent.EventType.CREATED
                    ),
                    dsl
                );
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
        if (execution.isTerminal()
            || !execution.state().is(State.Type.PAUSED)) {
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
        if (!taskRun.state().is(State.Type.PAUSED)) {
            return;
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
        Map<String, Object> normalizedOutputs = pause.validateResume(
            command.getOutputs()
        );
        execution.resumeTaskRun(taskRun.id(), normalizedOutputs);
        executionRepository.save(dsl, execution);
        eventQueue.emitInTransaction(
            ExecutorEvent.from(
                execution,
                ExecutorEvent.EventType.UPDATED
            ),
            dsl
        );
    }

    private void handleRewind(DSLContext dsl, Rewind command) {
        Execution execution = executionRepository.findById(
            dsl,
            command.getCompanyId(),
            command.getExecutionId()
        ).orElseThrow(() -> new WorkflowException(
            "Execution does not exist: " + command.getExecutionId()
        ));

        if (execution.isTerminal()
            || !execution.state().is(State.Type.PAUSED)) {
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
        execution.rewindTaskRun(
            command.getSourceTaskRunId(),
            command.getTargetTaskRunId(),
            command.getReason()
        );
        executionRepository.save(dsl, execution);
        eventQueue.emitInTransaction(
            ExecutorEvent.from(
                execution,
                ExecutorEvent.EventType.UPDATED
            ),
            dsl
        );
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
