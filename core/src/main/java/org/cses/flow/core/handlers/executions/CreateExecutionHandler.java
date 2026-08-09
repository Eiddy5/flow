package org.cses.flow.core.handlers.executions;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.executions.CreateExecutionCommand;
import org.cses.flow.core.commands.CommandContext;
import org.cses.flow.core.commands.CommandHandler;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.handlers.flows.FlowHandlerSupport;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.core.services.shared.SessionValidation;
import org.cses.flow.executor.DefaultExecutor;
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
    private final ExecutionRepository executionRepository;
    private final DefaultExecutor defaultExecutor;

    @Inject
    public CreateExecutionHandler(
        FlowRepository flowRepository,
        ExecutionRepository executionRepository,
        DefaultExecutor defaultExecutor
    ) {
        this.flowRepository = flowRepository;
        this.executionRepository = executionRepository;
        this.defaultExecutor = defaultExecutor;
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
        if (flow.isDeleted()) {
            throw new WorkflowException(
                "Only the latest undeleted Flow can start an Execution: "
                    + flow.id()
            );
        }
        Execution execution = Execution.create(
            companyId,
            flow.id(),
            flow.reversion()
        );
        if (!context.getCommand().startImmediately()) {
            executionRepository.save(context.getDsl(), execution);
            return execution.copy();
        }
        return defaultExecutor.execute(
            context.getSession(),
            context.getDsl(),
            new ExecutorContext(flow, execution)
        );
    }
}
