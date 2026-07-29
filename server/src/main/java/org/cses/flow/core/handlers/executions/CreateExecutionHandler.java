package org.cses.flow.core.handlers.executions;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.executions.CreateExecutionCommand;
import org.cses.flow.core.commands.shared.CommandContext;
import org.cses.flow.core.commands.shared.CommandHandler;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowStatus;
import org.cses.flow.core.exceptions.shared.WorkflowException;
import org.cses.flow.core.handlers.flows.FlowHandlerSupport;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.services.shared.SessionValidation;
import org.cses.flow.executor.ExecutorContext;
import org.paas.session.Session;
import org.paas.session.User;

@Singleton
public final class CreateExecutionHandler implements CommandHandler<
    Session<User>,
    User,
    Execution,
    CreateExecutionCommand
> {

    private final FlowRepository flowRepository;
    private final ExecutionHandler executionHandler;

    @Inject
    public CreateExecutionHandler(
        FlowRepository flowRepository,
        ExecutionHandler executionHandler
    ) {
        this.flowRepository = flowRepository;
        this.executionHandler = executionHandler;
    }

    @Override
    public Class<CreateExecutionCommand> commandType() {
        return CreateExecutionCommand.class;
    }

    @Override
    public Execution handle(
        CommandContext<
            Session<User>,
            User,
            Execution,
            CreateExecutionCommand
        > context
    ) {
        String companyId = SessionValidation.requireCompanyId(
            context.getSession()
        );
        Flow flow = FlowHandlerSupport.requireLatestFlow(
            flowRepository,
            context.getDsl(),
            companyId,
            context.getCommand().flowId()
        );
        if (flow.status() != FlowStatus.DEPLOYED) {
            throw new WorkflowException(
                "Only the latest DEPLOYED Flow can start an Execution: "
                    + flow.id()
            );
        }
        Execution execution = Execution.create(
            companyId,
            flow.id(),
            flow.reversion()
        );
        return executionHandler.handle(new ExecutorContext<>(
            context.getSession(),
            context.getDsl(),
            flow,
            execution
        ));
    }
}
