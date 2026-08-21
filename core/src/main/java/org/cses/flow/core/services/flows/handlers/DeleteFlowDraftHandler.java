package org.cses.flow.core.services.flows.handlers;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.services.flows.commands.DeleteFlowDraftCommand;
import org.cses.flow.core.services.CommandContext;
import org.cses.flow.core.services.CommandHandler;
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
            context.session()
        );
        FlowDraft draft = FlowHandlerSupport.requireDraft(
            repository,
            context.dsl(),
            companyId,
            context.command().key().trim()
        );
        draft.delete(
            context.session(),
            System.currentTimeMillis()
        );
        repository.save(context.dsl(), draft);
        return draft.copy();
    }
}
