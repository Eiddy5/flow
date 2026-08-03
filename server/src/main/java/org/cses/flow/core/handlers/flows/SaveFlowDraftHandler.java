package org.cses.flow.core.handlers.flows;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.flows.SaveFlowDraftCommand;
import org.cses.flow.core.commands.CommandContext;
import org.cses.flow.core.commands.CommandHandler;
import org.cses.flow.core.domains.flows.ActorRef;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.repositories.flows.FlowDraftRepository;
import org.cses.flow.core.services.shared.SessionValidation;
import org.paas.session.Session;
import org.paas.session.User;

@Singleton
public final class SaveFlowDraftHandler implements CommandHandler<
    Session<User>,
    User,
    FlowDraft,
    SaveFlowDraftCommand
> {

    private final FlowDraftRepository repository;

    @Inject
    public SaveFlowDraftHandler(FlowDraftRepository repository) {
        this.repository = repository;
    }

    @Override
    public Class<SaveFlowDraftCommand> commandType() {
        return SaveFlowDraftCommand.class;
    }

    @Override
    public FlowDraft handle(
        CommandContext<
            Session<User>,
            User,
            FlowDraft,
            SaveFlowDraftCommand
        > context
    ) {
        SaveFlowDraftCommand command = context.getCommand();
        String companyId = SessionValidation.requireCompanyId(
            context.getSession()
        );
        ActorRef actor = FlowHandlerSupport.actor(context.getSession());
        long now = System.currentTimeMillis();
        FlowDraft draft;
        if (command.id() == null) {
            draft = FlowDraft.create(
                companyId,
                command.raw(),
                actor,
                now
            );
        } else {
            draft = FlowHandlerSupport.requireDraft(
                repository,
                context.getDsl(),
                companyId,
                command.id()
            );
            draft.requireLockVersion(command.expectedLockVersion());
            draft.revise(command.raw(), actor, now);
        }
        repository.save(context.getDsl(), draft);
        return draft.copy();
    }
}
