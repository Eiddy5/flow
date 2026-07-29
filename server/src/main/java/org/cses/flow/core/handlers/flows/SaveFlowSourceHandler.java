package org.cses.flow.core.handlers.flows;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.flows.SaveFlowSourceCommand;
import org.cses.flow.core.commands.shared.CommandContext;
import org.cses.flow.core.commands.shared.CommandHandler;
import org.cses.flow.core.domains.flows.ActorRef;
import org.cses.flow.core.domains.flows.FlowWithSource;
import org.cses.flow.core.repositories.flows.FlowWithSourceRepository;
import org.cses.flow.core.services.shared.SessionValidation;
import org.paas.session.Session;
import org.paas.session.User;

import java.time.Instant;

@Singleton
public final class SaveFlowSourceHandler implements CommandHandler<
    Session<User>,
    User,
    FlowWithSource,
    SaveFlowSourceCommand
> {

    private final FlowWithSourceRepository repository;

    @Inject
    public SaveFlowSourceHandler(FlowWithSourceRepository repository) {
        this.repository = repository;
    }

    @Override
    public Class<SaveFlowSourceCommand> commandType() {
        return SaveFlowSourceCommand.class;
    }

    @Override
    public FlowWithSource handle(
        CommandContext<
            Session<User>,
            User,
            FlowWithSource,
            SaveFlowSourceCommand
        > context
    ) {
        SaveFlowSourceCommand command = context.getCommand();
        String companyId = SessionValidation.requireCompanyId(
            context.getSession()
        );
        ActorRef actor = FlowHandlerSupport.actor(context.getSession());
        Instant now = Instant.now();
        FlowWithSource source;
        if (command.id() == null) {
            source = FlowWithSource.create(
                companyId,
                command.raw(),
                actor,
                now
            );
        } else {
            source = FlowHandlerSupport.requireSource(
                repository,
                context.getDsl(),
                companyId,
                command.id()
            );
            source.requireLockVersion(command.expectedLockVersion());
            source.revise(command.raw(), actor, now);
        }
        repository.save(context.getDsl(), source);
        return source.copy();
    }
}
