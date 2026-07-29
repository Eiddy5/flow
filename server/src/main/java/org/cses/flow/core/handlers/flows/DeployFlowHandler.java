package org.cses.flow.core.handlers.flows;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.flows.DeployFlowCommand;
import org.cses.flow.core.commands.shared.CommandContext;
import org.cses.flow.core.commands.shared.CommandHandler;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowWithSource;
import org.cses.flow.core.domains.tasks.TaskTypeDispatcher;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.repositories.flows.FlowWithSourceRepository;
import org.cses.flow.core.serializers.YamlParser;
import org.cses.flow.core.services.shared.SessionValidation;
import org.paas.session.Session;
import org.paas.session.User;

import java.time.Instant;

@Singleton
public final class DeployFlowHandler implements CommandHandler<
    Session<User>,
    User,
    Flow,
    DeployFlowCommand
> {

    private final FlowWithSourceRepository sourceRepository;
    private final FlowRepository flowRepository;
    private final TaskTypeDispatcher taskTypeDispatcher;
    private final YamlParser yamlParser;

    @Inject
    public DeployFlowHandler(
        FlowWithSourceRepository sourceRepository,
        FlowRepository flowRepository,
        TaskTypeDispatcher taskTypeDispatcher,
        YamlParser yamlParser
    ) {
        this.sourceRepository = sourceRepository;
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
        FlowWithSource source = FlowHandlerSupport.requireSource(
            sourceRepository,
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
            yamlParser.parse(source.raw()),
            latest,
            taskTypeDispatcher,
            FlowHandlerSupport.actor(context.getSession()),
            Instant.now()
        );
        flowRepository.save(context.getDsl(), deployed);
        return deployed.copy();
    }
}
