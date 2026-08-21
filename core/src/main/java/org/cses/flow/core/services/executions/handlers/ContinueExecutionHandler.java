package org.cses.flow.core.services.executions.handlers;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.services.executions.commands.ContinueExecutionCommand;
import org.cses.flow.core.services.CommandContext;
import org.cses.flow.core.services.CommandHandler;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.core.services.shared.SessionValidation;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Map;

/**
 * Idempotently stages a durable start command for a pending Execution.
 */
@Singleton
public final class ContinueExecutionHandler implements CommandHandler<
    Session<User>,
    User,
    Execution,
    ContinueExecutionCommand
> {

    private final ExecutionRepository executionRepository;

    @Inject
    public ContinueExecutionHandler(
        ExecutionRepository executionRepository
    ) {
        this.executionRepository = executionRepository;
    }

    @Override
    public Class<ContinueExecutionCommand> commandType() {
        return ContinueExecutionCommand.class;
    }

    @Override
    public Execution handle(
        CommandContext<
            Session<User>,
            User,
            Execution,
            ContinueExecutionCommand
        > context
    ) {
        String companyId = SessionValidation.requireCompanyId(
            context.session()
        );
        Execution execution = executionRepository.lockById(
            context.dsl(),
            companyId,
            context.command().executionId()
        ).orElseThrow(() -> new WorkflowException(
            "Execution does not exist: "
                + context.command().executionId()
        ));
        if (!execution.state().is(State.Type.CREATED)) {
            return execution.copy();
        }
        Map<String, Object> inputs = context.command().inputs();
        boolean inputsChanged = !execution.inputs().equals(inputs);
        execution.bindInputs(inputs);
        if (inputsChanged) {
            executionRepository.save(context.dsl(), execution);
        }
        return execution.copy();
    }
}
