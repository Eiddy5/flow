package org.cses.flow.core.services.flows;

import jakarta.inject.Singleton;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.services.CommandExecutor;
import org.cses.flow.core.services.flows.commands.DeleteFlowCommand;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.cses.flow.core.services.flows.queries.FlowQueryHandler;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.List;
import java.util.Optional;

@Singleton
public class FlowService {

    CommandExecutor commandExecutor;
    FlowQueryHandler queryHandler;

    public FlowService(
            CommandExecutor commandExecutor,
            FlowQueryHandler queryHandler
    ) {
        this.commandExecutor = commandExecutor;
        this.queryHandler = queryHandler;
    }

    public <S extends Session<U>, U extends User> Flow save(
            S session,
            PublishFlowCommand command
    ) {
        return commandExecutor.execute(session, command);
    }

    public <S extends Session<U>, U extends User> Flow delete(
            S session,
            String flowKey,
            Boolean draft
    ) {

        return commandExecutor.execute(
                session,
                DeleteFlowCommand.from(flowKey, draft)
        );
    }

    public <S extends Session<U>, U extends User> List<Flow> drafts(S session) {

        return queryHandler.drafts(session);
    }

    public <S extends Session<U>, U extends User> Optional<Flow> draft(
            S session,
            String flowKey
    ) {
        return queryHandler.draftByFlowKey(session, flowKey);
    }

    public <S extends Session<U>, U extends User> Optional<Flow> flow(
            S session,
            String flowKey,
            long flowVersion
    ) {

        return queryHandler.flow(session, flowKey, flowVersion);
    }

    public <S extends Session<U>, U extends User> Optional<Flow> latestFlow(
            S session,
            String flowKey
    ) {

        return queryHandler.latestFlow(session, flowKey);
    }

}
