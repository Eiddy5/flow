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
import org.cses.flow.executor.ExecutorService;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.cses.flow.queues.DispatchQueue;
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
public class ExecutorEventHandler implements
        org.cses.flow.executor.ExecutorEventHandler<ExecutorEvent> {

    private JOOQ jooq;
    private SessionFactory<?, ?> sessionFactory;
    private FlowRepository flowRepository;
    private ExecutionRepository executionRepository;
    private ExecutorService executorService;
    private WorkerDispatcher workerDispatcher;
    private DispatchQueue<ExecutorEvent> eventQueue;

    @Inject
    public ExecutorEventHandler(
            @Named(FlowDatabase.DATA_SOURCE_NAME) JOOQ jooq,
            SessionFactory<?, ?> sessionFactory,
            FlowRepository flowRepository,
            ExecutionRepository executionRepository,
            ExecutorService executorService,
            WorkerDispatcher workerDispatcher,
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
        this.executorService = Objects.requireNonNull(
                executorService,
                "executorService"
        );
        this.workerDispatcher = Objects.requireNonNull(
                workerDispatcher,
                "workerDispatcher"
        );
        this.eventQueue = Objects.requireNonNull(eventQueue, "eventQueue");
    }

    /**
     * Constructor for state-machine tests that exercise the handler's local
     * cycle without starting a database-backed Queue consumer.
     */
    public ExecutorEventHandler(
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

    @Override
    public Optional<ExecutorContext> handle(ExecutorEvent event) {
        ExecutorEvent accepted = Objects.requireNonNull(
                event,
                "event"
        );
        requireProductionRuntime();
        AtomicReference<ExecutorContext> processed =
                new AtomicReference<>();
        jooq.run(dsl -> processed.set(process(dsl, accepted)));
        return Optional.ofNullable(processed.get());
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
        return processed.get();
    }

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
        if (workerTasks.isEmpty()) {
            if (executionUpdated && context.canBeProcessed()) {
                emitNext(dsl, event);
            }
            return context;
        }

        for (WorkerTask workerTask : workerTasks) {
            workerTask = executorService.dispatch(context, workerTask);
            executionUpdated |= persistIfUpdated(dsl, context);

            WorkerTaskResult result = dispatchWorkerTask(
                    workerTask,
                    event.eventType() != ExecutorEvent.EventType.TERMINATED
            );
            executorService.applyResult(context, result);
            executionUpdated |= persistIfUpdated(dsl, context);
            if (result.targetState() == State.Type.FAILED
                    || result.targetState() == State.Type.KILLED) {
                return context;
            }
        }

        if (executionUpdated && context.canBeProcessed()) {
            emitNext(dsl, event);
        }
        return context;
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

    private void emitNext(DSLContext dsl, ExecutorEvent event) {
        eventQueue.emitInTransaction(event.nextUpdate(), dsl);
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
