package org.cses.flow.executor.handlers;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.CommandContext;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.handlers.flows.FlowHandlerSupport;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.executor.ExecutionRunner;
import org.cses.flow.executor.ExecutorContext;
import org.cses.flow.executor.commands.Create;
import org.cses.flow.executor.commands.ExecutorCommand;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.jooq.DSLContext;
import org.paas.session.Session;
import org.paas.session.SessionFactory;
import org.paas.session.User;
import org.x9.jooq.JOOQ;

import java.util.List;
import java.util.Objects;

/**
 * Handles every command accepted by the Executor command Queue.
 */
@Singleton
public final class ExecutorCommandHandler {

    private final JOOQ jooq;
    private final SessionFactory<?, ?> sessionFactory;
    private final FlowRepository flowRepository;
    private final ExecutionRepository executionRepository;
    private final ExecutionRunner executionRunner;

    @Inject
    public ExecutorCommandHandler(
        @Named(FlowDatabase.DATA_SOURCE_NAME) JOOQ jooq,
        SessionFactory<?, ?> sessionFactory,
        FlowRepository flowRepository,
        ExecutionRepository executionRepository,
        ExecutionRunner executionRunner
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
        this.executionRunner = Objects.requireNonNull(
            executionRunner,
            "executionRunner"
        );
    }

    public void handle(ExecutorCommand command) {
        ExecutorCommand accepted = Objects.requireNonNull(
            command,
            "command"
        );
        accepted.validate();
        Session<?> session = restoreSession(accepted);
        jooq.run(dsl -> inCommandScope(
            dsl,
            session,
            accepted,
            () -> route(session, dsl, accepted)
        ));
    }

    private void route(
        Session<?> session,
        DSLContext dsl,
        ExecutorCommand command
    ) {
        switch (command) {
            case Create create -> handleCreate(session, dsl, create);
        }
    }

    private void handleCreate(
        Session<?> session,
        DSLContext dsl,
        Create command
    ) {
        Flow flow = FlowHandlerSupport.requireFlow(
            flowRepository,
            dsl,
            command.getCompanyId(),
            command.getFlowId(),
            command.getFlowReversion()
        );

        Execution execution = executionRepository.lockById(
            dsl,
            command.getCompanyId(),
            command.getExecutionId()
        ).orElse(null);
        if (execution != null) {
            requireSameCreate(command, execution);
            if (execution.isTerminal() || execution.state().isPaused()) {
                return;
            }
        } else {
            execution = Execution.create(
                command.getExecutionId(),
                command.getCompanyId(),
                command.getFlowId(),
                command.getFlowReversion()
            );
        }

        executionRunner.execute(
            session,
            dsl,
            new ExecutorContext(
                flow,
                execution,
                flow.normalizeInputs(command.getInputs())
            )
        );
    }

    private static void requireSameCreate(
        Create command,
        Execution execution
    ) {
        if (!execution.flowId().equals(command.getFlowId())
            || execution.flowReversion() != command.getFlowReversion()) {
            throw new WorkflowException(
                "Execution id already belongs to another Flow reference: "
                    + command.getExecutionId()
            );
        }
    }

    private static void inCommandScope(
        DSLContext dsl,
        Session<?> session,
        ExecutorCommand command,
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
    private Session<?> restoreSession(ExecutorCommand command) {
        Session restored = sessionFactory.session();
        User user = restoreUser(command);
        user.setId(command.getActorId());
        if (command.getActorName() != null) {
            user.setName(command.getActorName());
            user.setUserName(command.getActorName());
        }
        user.setCompanyId(command.getCompanyId());

        restored.setId(command.getSessionId());
        restored.setIp(command.getIp());
        restored.setDevice(command.getDevice());
        restored.setDeviceId(command.getDeviceId());
        restored.setCompanyId(command.getCompanyId());
        restored.setAppVersion(command.getAppVersion());
        restored.setOsVersion(command.getOsVersion());
        restored.setUser(user);
        user.onSessionBound();
        return restored;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private User restoreUser(ExecutorCommand command) {
        List<? extends User> users = ((SessionFactory) sessionFactory)
            .buildSessionUser(List.of(command.getActorId()));
        if (users != null && !users.isEmpty()) {
            return users.getFirst();
        }
        return sessionFactory.user();
    }
}
