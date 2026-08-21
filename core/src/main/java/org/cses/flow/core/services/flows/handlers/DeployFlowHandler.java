package org.cses.flow.core.services.flows.handlers;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.services.flows.commands.DeployFlowCommand;
import org.cses.flow.core.services.CommandContext;
import org.cses.flow.core.services.CommandHandler;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.exceptions.WorkflowException;
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
            CommandContext<Session<User>,
                    User,
                    Flow,
                    DeployFlowCommand>
                    context
    ) {
        String companyId = SessionValidation.requireCompanyId(
                context.session()
        );
        String flowKey = context.command().key().trim();
        FlowDraft draft = FlowHandlerSupport.requireDraft(
                draftRepository,
                context.dsl(),
                companyId,
                flowKey
        );
        Flow latestForDraftKey = flowRepository.findLatestByKey(
                context.dsl(),
                companyId,
                flowKey
        ).orElse(null);
        flowDefinitionDeserializer
                .declaredFlowKey(draft.raw())
                .filter(declaredFlowKey -> !flowKey.equals(declaredFlowKey))
                .ifPresent(declaredFlowKey -> {
                    throw new WorkflowException(
                            "Flow key cannot change across reversion: "
                                    + flowKey + " -> " + declaredFlowKey
                    );
                });
        Flow deployed = flowDefinitionDeserializer.deserialize(
                draft.raw(),
                companyId,
                flowKey,
                latestForDraftKey,
                FlowHandlerSupport.actor(context.session()),
                System.currentTimeMillis()
        );
        flowRepository.save(context.dsl(), deployed);
        return deployed.copy();
    }
}
