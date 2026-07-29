package org.cses.flow.core.services.flows;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.flows.CloseFlowCommand;
import org.cses.flow.core.commands.flows.DeployFlowCommand;
import org.cses.flow.core.commands.flows.DiscardFlowSourceCommand;
import org.cses.flow.core.commands.flows.SaveFlowSourceCommand;
import org.cses.flow.core.commands.shared.CommandExecutor;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowWithSource;
import org.cses.flow.core.exceptions.shared.WorkflowException;
import org.cses.flow.core.queries.flows.FlowQueryHandler;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Optional;

@Singleton
public final class FlowService {

    private final CommandExecutor commandExecutor;
    private final FlowQueryHandler queryHandler;

    @Inject
    public FlowService(
        CommandExecutor commandExecutor,
        FlowQueryHandler queryHandler
    ) {
        this.commandExecutor = commandExecutor;
        this.queryHandler = queryHandler;
    }

    public <S extends Session<U>, U extends User>
        FlowWithSource saveDraft(S session, String raw) {

        return commandExecutor.execute(
            session,
            new SaveFlowSourceCommand(null, null, raw)
        );
    }

    public <S extends Session<U>, U extends User>
        FlowWithSource saveDraft(
            S session,
            String id,
            String raw
        ) {

        FlowWithSource existing = source(session, id).orElseThrow(() ->
            new WorkflowException(
                "FlowWithSource does not exist: " + id
            )
        );
        return saveDraft(
            session,
            id,
            existing.lockVersion(),
            raw
        );
    }

    public <S extends Session<U>, U extends User>
        FlowWithSource saveDraft(
            S session,
            String id,
            long expectedLockVersion,
            String raw
        ) {

        return commandExecutor.execute(
            session,
            new SaveFlowSourceCommand(
                id,
                expectedLockVersion,
                raw
            )
        );
    }

    public <S extends Session<U>, U extends User>
        Flow deploy(S session, String id) {

        return commandExecutor.execute(
            session,
            new DeployFlowCommand(id)
        );
    }

    public <S extends Session<U>, U extends User>
        FlowWithSource discardDraft(S session, String id) {

        return commandExecutor.execute(
            session,
            new DiscardFlowSourceCommand(id)
        );
    }

    public <S extends Session<U>, U extends User>
        Flow close(S session, String id) {

        return commandExecutor.execute(
            session,
            new CloseFlowCommand(id)
        );
    }

    public <S extends Session<U>, U extends User>
        Optional<FlowWithSource> source(S session, String flowId) {

        return queryHandler.source(session, flowId);
    }

    public <S extends Session<U>, U extends User>
        Optional<Flow> flow(
            S session,
            String flowId,
            long reversion
        ) {

        return queryHandler.flow(session, flowId, reversion);
    }

    public <S extends Session<U>, U extends User>
        Optional<Flow> latestFlow(S session, String flowId) {

        return queryHandler.latestFlow(session, flowId);
    }
}
