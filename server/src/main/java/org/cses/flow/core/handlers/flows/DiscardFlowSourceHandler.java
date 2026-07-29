package org.cses.flow.core.handlers.flows;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.flows.DiscardFlowSourceCommand;
import org.cses.flow.core.commands.shared.CommandContext;
import org.cses.flow.core.commands.shared.CommandHandler;
import org.cses.flow.core.domains.flows.FlowWithSource;
import org.cses.flow.core.repositories.flows.FlowWithSourceRepository;
import org.cses.flow.core.services.shared.SessionValidation;
import org.paas.session.Session;
import org.paas.session.User;

import java.time.Instant;

@Singleton
public final class DiscardFlowSourceHandler implements CommandHandler<
    Session<User>,
    User,
    FlowWithSource,
    DiscardFlowSourceCommand
> {

    private final FlowWithSourceRepository repository;

    @Inject
    public DiscardFlowSourceHandler(
        FlowWithSourceRepository repository
    ) {
        this.repository = repository;
    }

    @Override
    public Class<DiscardFlowSourceCommand> commandType() {
        return DiscardFlowSourceCommand.class;
    }

    @Override
    public FlowWithSource handle(
        CommandContext<
            Session<User>,
            User,
            FlowWithSource,
            DiscardFlowSourceCommand
        > context
    ) {
        String companyId = SessionValidation.requireCompanyId(
            context.getSession()
        );
        FlowWithSource source = FlowHandlerSupport.requireSource(
            repository,
            context.getDsl(),
            companyId,
            context.getCommand().id()
        );
        source.discard(
            FlowHandlerSupport.actor(context.getSession()),
            Instant.now()
        );
        repository.save(context.getDsl(), source);
        return source.copy();
    }
}
