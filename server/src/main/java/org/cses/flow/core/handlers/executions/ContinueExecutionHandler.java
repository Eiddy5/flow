package org.cses.flow.core.handlers.executions;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.executions.ContinueExecutionCommand;
import org.cses.flow.core.commands.shared.CommandContext;
import org.cses.flow.core.commands.shared.CommandHandler;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.ExecutionStatus;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.exceptions.shared.WorkflowException;
import org.cses.flow.core.handlers.flows.FlowHandlerSupport;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.services.shared.SessionValidation;
import org.cses.flow.executor.ExecutorContext;
import org.paas.session.Session;
import org.paas.session.User;

/**
 * Rebuilds and drives a non-terminal Execution from persisted facts.
 */
@Singleton
public final class ContinueExecutionHandler implements CommandHandler<
    Session<User>,
    User,
    Execution,
    ContinueExecutionCommand
> {

    private final ExecutionRepository executionRepository;
    private final FlowRepository flowRepository;
    private final ExecutionHandler executionHandler;

    @Inject
    public ContinueExecutionHandler(
        ExecutionRepository executionRepository,
        FlowRepository flowRepository,
        ExecutionHandler executionHandler
    ) {
        this.executionRepository = executionRepository;
        this.flowRepository = flowRepository;
        this.executionHandler = executionHandler;
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
        Execution execution = executionRepository.findById(
            context.getDsl(),
            companyId,
            context.getCommand().executionId()
        ).orElseThrow(() -> new WorkflowException(
            "Execution does not exist: "
                + context.getCommand().executionId()
        ));
        if (execution.isTerminal()) {
            return execution.copy();
        }
        Flow flow = FlowHandlerSupport.requireFlow(
            flowRepository,
            context.getDsl(),
            companyId,
            execution.flowId(),
            execution.flowReversion()
        );
        return executionHandler.continueExecution(new ExecutorContext<>(
            context.getSession(),
            context.getDsl(),
            flow,
            execution
        ));
    }
}
