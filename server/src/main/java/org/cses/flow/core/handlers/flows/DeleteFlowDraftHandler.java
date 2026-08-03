package org.cses.flow.core.handlers.flows;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.flows.DeleteFlowDraftCommand;
import org.cses.flow.core.commands.CommandContext;
import org.cses.flow.core.commands.CommandHandler;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.repositories.flows.FlowDraftRepository;
import org.cses.flow.core.services.shared.SessionValidation;
import org.paas.session.Session;
import org.paas.session.User;

@Singleton
public final class DeleteFlowDraftHandler implements CommandHandler<
    Session<User>,
    User,
    FlowDraft,
    DeleteFlowDraftCommand
> {

    private final FlowDraftRepository repository;

    @Inject
    public DeleteFlowDraftHandler(
        FlowDraftRepository repository
    ) {
        this.repository = repository;
    }

    @Override
    public Class<DeleteFlowDraftCommand> commandType() {
        return DeleteFlowDraftCommand.class;
    }

    @Override
    public FlowDraft handle(
        CommandContext<
            Session<User>,
            User,
            FlowDraft,
            DeleteFlowDraftCommand
        > context
    ) {
        String companyId = SessionValidation.requireCompanyId(
            context.getSession()
        );
        FlowDraft draft = FlowHandlerSupport.requireDraft(
            repository,
            context.getDsl(),
            companyId,
            context.getCommand().id()
        );
        draft.delete(
            FlowHandlerSupport.actor(context.getSession()),
            System.currentTimeMillis()
        );
        repository.save(context.getDsl(), draft);
        return draft.copy();
    }
}
