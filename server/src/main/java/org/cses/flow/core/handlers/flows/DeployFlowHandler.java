package org.cses.flow.core.handlers.flows;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.flows.DeployFlowCommand;
import org.cses.flow.core.commands.CommandContext;
import org.cses.flow.core.commands.CommandHandler;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.plugins.TaskTypeDispatcher;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.repositories.flows.FlowDraftRepository;
import org.cses.flow.core.serializers.YamlParser;
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
    private final TaskTypeDispatcher taskTypeDispatcher;
    private final YamlParser yamlParser;

    @Inject
    public DeployFlowHandler(
        FlowDraftRepository draftRepository,
        FlowRepository flowRepository,
        TaskTypeDispatcher taskTypeDispatcher,
        YamlParser yamlParser
    ) {
        this.draftRepository = draftRepository;
        this.flowRepository = flowRepository;
        this.taskTypeDispatcher = taskTypeDispatcher;
        this.yamlParser = yamlParser;
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
        String flowId = context.getCommand().id();
        FlowDraft draft = FlowHandlerSupport.requireDraft(
            draftRepository,
            context.getDsl(),
            companyId,
            flowId
        );
        Flow latest = flowRepository.findLatest(
            context.getDsl(),
            companyId,
            flowId
        ).orElse(null);
        Flow deployed = Flow.deploy(
            companyId,
            flowId,
            yamlParser.parse(draft.raw()),
            latest,
            taskTypeDispatcher,
            FlowHandlerSupport.actor(context.getSession()),
            System.currentTimeMillis()
        );
        flowRepository.save(context.getDsl(), deployed);
        return deployed.copy();
    }
}
