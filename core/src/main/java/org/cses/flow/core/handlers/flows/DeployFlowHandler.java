package org.cses.flow.core.handlers.flows;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.flows.DeployFlowCommand;
import org.cses.flow.core.commands.CommandContext;
import org.cses.flow.core.commands.CommandHandler;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.repositories.flows.FlowDraftRepository;
import org.cses.flow.core.serializers.FlowDefinitionDeserializer;
import org.cses.flow.core.services.shared.SessionValidation;
import org.paas.session.Session;
import org.paas.session.User;

@Singleton
public final class DeployFlowHandler implements CommandHandler<
    Session<User>,
    User,
    Flow,
    DeployFlowCommand
> {

    private final FlowDraftRepository draftRepository;
    private final FlowRepository flowRepository;
    private final FlowDefinitionDeserializer flowDefinitionDeserializer;

    @Inject
    public DeployFlowHandler(
        FlowDraftRepository draftRepository,
        FlowRepository flowRepository,
        FlowDefinitionDeserializer flowDefinitionDeserializer
    ) {
        this.draftRepository = draftRepository;
        this.flowRepository = flowRepository;
        this.flowDefinitionDeserializer = flowDefinitionDeserializer;
    }

    @Override
    public Class<DeployFlowCommand> commandType() {
        return DeployFlowCommand.class;
    }

    @Override
    public Flow handle(
        CommandContext<
            Session<User>,
            User,
            Flow,
            DeployFlowCommand
        > context
    ) {
        String companyId = SessionValidation.requireCompanyId(
            context.getSession()
        );
        String flowKey = context.getCommand().id();
        FlowDraft draft = FlowHandlerSupport.requireDraft(
            draftRepository,
            context.getDsl(),
            companyId,
            flowKey
        );
        Flow latest = flowRepository.findLatestByKey(
            context.getDsl(),
            companyId,
            flowKey
        ).orElse(null);
        Flow deployed = flowDefinitionDeserializer.deserialize(
            draft.raw(),
            companyId,
            flowKey,
            latest,
            FlowHandlerSupport.actor(context.getSession()),
            System.currentTimeMillis()
        );
        flowRepository.save(context.getDsl(), deployed);
        return deployed.copy();
    }
}
