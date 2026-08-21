package org.cses.flow.core.services.flows.handlers;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.services.flows.commands.SaveFlowDraftCommand;
import org.cses.flow.core.services.CommandContext;
import org.cses.flow.core.services.CommandHandler;
import org.cses.flow.core.domains.ActorRef;
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
        SaveFlowDraftCommand command = context.command();
        String companyId = SessionValidation.requireCompanyId(
                context.session()
        );
        ActorRef actor = FlowHandlerSupport.actor(context.session());
        long now = System.currentTimeMillis();
        String flowKey = command.key().trim();
        FlowDraft draft = repository.lockByFlowKey(
                context.dsl(),
                companyId,
                flowKey
        ).orElse(null);
        if (draft == null) {
            draft = FlowDraft.create(
                    companyId,
                    flowKey,
                    command.raw(),
                    actor,
                    now
            );
        } else {
            if (command.expectedLockVersion() != null) {
                draft.requireLockVersion(command.expectedLockVersion());
            }
            draft.revise(command.raw(), context.session(), now);
        }
        repository.save(context.dsl(), draft);
        return draft.copy();
    }
}
