package org.cses.flow.core.handlers.executions;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.executions.ContinueExecutionCommand;
import org.cses.flow.core.commands.CommandContext;
import org.cses.flow.core.commands.CommandHandler;
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
            context.getSession()
        );
        Execution execution = executionRepository.lockById(
            context.getDsl(),
            companyId,
            context.getCommand().executionId()
        ).orElseThrow(() -> new WorkflowException(
            "Execution does not exist: "
                + context.getCommand().executionId()
        ));
        if (!execution.state().is(State.Type.CREATED)) {
            return execution.copy();
        }
        Map<String, Object> inputs = context.getCommand().inputs();
        boolean inputsChanged = !execution.inputs().equals(inputs);
        execution.bindInputs(inputs);
        if (inputsChanged) {
            executionRepository.save(context.getDsl(), execution);
        }
        return execution.copy();
    }
}
