package org.cses.flow.core.services.executions.handlers;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.services.executions.commands.CreateExecutionCommand;
import org.cses.flow.core.services.CommandContext;
import org.cses.flow.core.services.CommandHandler;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.services.flows.handlers.FlowHandlerSupport;
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
                context.session()
        );
        CreateExecutionCommand command = context.command();
        Flow flow = command.expectedFlowVersion() == null
                ? FlowHandlerSupport.requireLatestFlow(
                flowRepository,
                context.dsl(),
                companyId,
                command.flowKey()
        )
                : FlowHandlerSupport.requireFlow(
                flowRepository,
                context.dsl(),
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
                flow.key(),
                flow.reversion(),
                Map.of()
        )
                : Execution.create(
                command.executionId(),
                companyId,
                flow.key(),
                flow.reversion(),
                Map.of()
        );
        executionRepository.save(context.dsl(), execution);
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
                context.dsl(),
                companyId,
                command.executionId()
        );
        existing.ifPresent(execution -> {
            if (!execution.flowKey().equals(flow.key())
                    || execution.flowVersion() != flow.reversion()) {
                throw new WorkflowException(
                        "Execution id already belongs to another Flow reference: "
                                + command.executionId()
                );
            }
        });
        return existing;
    }
}
