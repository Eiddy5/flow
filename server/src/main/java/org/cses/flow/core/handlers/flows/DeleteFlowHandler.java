package org.cses.flow.core.handlers.flows;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.flows.DeleteFlowCommand;
import org.cses.flow.core.commands.CommandContext;
import org.cses.flow.core.commands.CommandHandler;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.repositories.flows.FlowDraftRepository;
import org.cses.flow.core.services.shared.SessionValidation;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Optional;

@Singleton
public final class DeleteFlowHandler implements CommandHandler<
    Session<User>,
    User,
    Flow,
    DeleteFlowCommand
> {

    private final FlowRepository flowRepository;
    private final FlowDraftRepository draftRepository;

    @Inject
    public DeleteFlowHandler(
        FlowRepository flowRepository,
        FlowDraftRepository draftRepository
    ) {
        this.flowRepository = flowRepository;
        this.draftRepository = draftRepository;
    }

    @Override
    public Class<DeleteFlowCommand> commandType() {
        return DeleteFlowCommand.class;
    }

    @Override
    public Flow handle(
        CommandContext<
            Session<User>,
            User,
            Flow,
            DeleteFlowCommand
        > context
    ) {
        DeleteFlowCommand command = context.getCommand();
        String companyId = SessionValidation.requireCompanyId(
            context.getSession()
        );
        Optional<FlowDraft> draft = draftRepository.lockById(
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
        flow.delete(actor, now);
        if (draft.isPresent()) {
            FlowDraft editableDraft = draft.orElseThrow();
            editableDraft.delete(actor, now);
            draftRepository.save(context.getDsl(), editableDraft);
        }
        flowRepository.save(context.getDsl(), flow);
        return flow.copy();
    }
}
