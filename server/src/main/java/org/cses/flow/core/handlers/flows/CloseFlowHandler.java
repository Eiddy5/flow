package org.cses.flow.core.handlers.flows;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.flows.CloseFlowCommand;
import org.cses.flow.core.commands.shared.CommandContext;
import org.cses.flow.core.commands.shared.CommandHandler;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowWithSource;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.repositories.flows.FlowWithSourceRepository;
import org.cses.flow.core.services.shared.SessionValidation;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Optional;

@Singleton
public final class CloseFlowHandler implements CommandHandler<
    Session<User>,
    User,
    Flow,
    CloseFlowCommand
> {

    private final FlowRepository flowRepository;
    private final FlowWithSourceRepository sourceRepository;

    @Inject
    public CloseFlowHandler(
        FlowRepository flowRepository,
        FlowWithSourceRepository sourceRepository
    ) {
        this.flowRepository = flowRepository;
        this.sourceRepository = sourceRepository;
    }

    @Override
    public Class<CloseFlowCommand> commandType() {
        return CloseFlowCommand.class;
    }

    @Override
    public Flow handle(
        CommandContext<
            Session<User>,
            User,
            Flow,
            CloseFlowCommand
        > context
    ) {
        CloseFlowCommand command = context.getCommand();
        String companyId = SessionValidation.requireCompanyId(
            context.getSession()
        );
        Optional<FlowWithSource> source = sourceRepository.lockById(
            context.getDsl(),
            companyId,
            command.id()
        );
        Flow flow = FlowHandlerSupport.requireLatestFlow(
            flowRepository,
            context.getDsl(),
            companyId,
            command.id()
        );
        var actor = FlowHandlerSupport.actor(context.getSession());
        long now = System.currentTimeMillis();
        flow.close(actor, now);
        if (source.isPresent()) {
            FlowWithSource editableSource = source.orElseThrow();
            editableSource.discard(actor, now);
            sourceRepository.save(context.getDsl(), editableSource);
        }
        flowRepository.save(context.getDsl(), flow);
        return flow.copy();
    }
}
