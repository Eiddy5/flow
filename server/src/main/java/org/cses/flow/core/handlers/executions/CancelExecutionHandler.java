package org.cses.flow.core.handlers.executions;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.executions.CancelExecutionCommand;
import org.cses.flow.core.commands.shared.CommandContext;
import org.cses.flow.core.commands.shared.CommandHandler;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.exceptions.shared.WorkflowException;
import org.cses.flow.core.handlers.flows.FlowHandlerSupport;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.services.shared.SessionValidation;
import org.cses.flow.executor.ExecutorContext;
import org.paas.session.Session;
import org.paas.session.User;

@Singleton
public final class CancelExecutionHandler implements CommandHandler<
    Session<User>,
    User,
    Execution,
    CancelExecutionCommand
> {

    private final ExecutionRepository executionRepository;
    private final FlowRepository flowRepository;
    private final ExecutionHandler executionHandler;

    @Inject
    public CancelExecutionHandler(
        ExecutionRepository executionRepository,
        FlowRepository flowRepository,
        ExecutionHandler executionHandler
    ) {
        this.executionRepository = executionRepository;
        this.flowRepository = flowRepository;
        this.executionHandler = executionHandler;
    }

    @Override
    public Class<CancelExecutionCommand> commandType() {
        return CancelExecutionCommand.class;
    }

    @Override
    public Execution handle(
        CommandContext<
            Session<User>,
            User,
            Execution,
            CancelExecutionCommand
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
        Flow flow = FlowHandlerSupport.requireFlow(
            flowRepository,
            context.getDsl(),
            companyId,
            execution.flowId(),
            execution.flowReversion()
        );
        return executionHandler.cancel(new ExecutorContext<>(
            context.getSession(),
            context.getDsl(),
            flow,
            execution
        ));
    }
}
