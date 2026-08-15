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
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Map;
import java.util.Optional;

@Singleton
public final class CreateExecutionHandler implements CommandHandler<
    Session<User>,
    User,
    Execution,
    CreateExecutionCommand
> {

    private final FlowRepository flowRepository;
    private final ExecutionRepository executionRepository;

    @Inject
    public CreateExecutionHandler(
        FlowRepository flowRepository,
        ExecutionRepository executionRepository
    ) {
        this.flowRepository = flowRepository;
        this.executionRepository = executionRepository;
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
        CreateExecutionCommand command = context.getCommand();
        Flow flow = command.expectedFlowVersion() == null
            ? FlowHandlerSupport.requireLatestFlow(
                flowRepository,
                context.getDsl(),
                companyId,
                command.flowKey()
            )
            : FlowHandlerSupport.requireFlow(
                flowRepository,
                context.getDsl(),
                companyId,
                command.flowKey(),
                command.expectedFlowVersion()
            );
        Optional<Execution> existing = existing(
            context,
            companyId,
            command,
            flow
        );
        if (existing.isPresent()) {
            return existing.get().copy();
        }
        if (flow.isDeleted()) {
            throw new WorkflowException(
                "Only an undeleted Flow can start an Execution: "
                    + flow.id()
            );
        }
        Execution execution = command.executionId() == null
            ? Execution.create(
                companyId,
                flow.id(),
                flow.reversion(),
                Map.of()
            )
            : Execution.create(
                command.executionId(),
                companyId,
                flow.id(),
                flow.reversion(),
                Map.of()
            );
        executionRepository.save(context.getDsl(), execution);
        return execution.copy();
    }

    private Optional<Execution> existing(
        CommandContext<
            Session<User>,
            User,
            Execution,
            CreateExecutionCommand
        > context,
        String companyId,
        CreateExecutionCommand command,
        Flow flow
    ) {
        if (command.executionId() == null) {
            return Optional.empty();
        }
        Optional<Execution> existing = executionRepository.findById(
            context.getDsl(),
            companyId,
            command.executionId()
        );
        existing.ifPresent(execution -> {
            if (!execution.flowId().equals(flow.id())
                || execution.flowReversion() != flow.reversion()) {
                throw new WorkflowException(
                    "Execution id already belongs to another Flow reference: "
                        + command.executionId()
                );
            }
        });
        return existing;
    }
}
