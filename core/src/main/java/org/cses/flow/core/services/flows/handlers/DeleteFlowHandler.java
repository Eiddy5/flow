package org.cses.flow.core.services.flows.handlers;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.services.flows.commands.DeleteFlowCommand;
import org.cses.flow.core.services.CommandContext;
import org.cses.flow.core.services.CommandHandler;
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
        DeleteFlowCommand command = context.command();
        String companyId = SessionValidation.requireCompanyId(
            context.session()
        );
        String flowKey = command.key().trim();
        Optional<FlowDraft> draft = draftRepository.lockByFlowKey(
            context.dsl(),
            companyId,
            flowKey
        );
        Flow flow = FlowHandlerSupport.requireLatestFlow(
            flowRepository,
            context.dsl(),
            companyId,
            flowKey
        );
        long now = System.currentTimeMillis();
        flow.delete(context.session(), now);
        if (draft.isPresent()) {
            FlowDraft editableDraft = draft.orElseThrow();
            editableDraft.delete(context.session(), now);
            draftRepository.save(context.dsl(), editableDraft);
        }
        flowRepository.save(context.dsl(), flow);
        return flow.copy();
    }
}
