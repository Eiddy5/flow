package org.cses.flow.core.services.flows;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.flows.DeleteFlowCommand;
import org.cses.flow.core.commands.flows.DeleteFlowDraftCommand;
import org.cses.flow.core.commands.flows.DeployFlowCommand;
import org.cses.flow.core.commands.flows.SaveFlowDraftCommand;
import org.cses.flow.core.commands.CommandExecutor;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.queries.flows.FlowQueryHandler;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.List;
import java.util.Optional;

@Singleton
public final class FlowService {

    private final CommandExecutor commandExecutor;
    private final FlowQueryHandler queryHandler;

    @Inject
    public FlowService(CommandExecutor commandExecutor, FlowQueryHandler queryHandler) {
        this.commandExecutor = commandExecutor;
        this.queryHandler = queryHandler;
    }

    public <S extends Session<U>, U extends User> FlowDraft saveDraft(S session, String raw) {
        return commandExecutor.execute(session, new SaveFlowDraftCommand(null, null, raw));
    }

    public <S extends Session<U>, U extends User> FlowDraft saveDraft(S session, String id, String raw) {

        FlowDraft existing = draft(session, id).orElseThrow(() -> new WorkflowException("FlowDraft does not exist: " + id));
        return saveDraft(session, id, existing.lockVersion(), raw);
    }

    public <S extends Session<U>, U extends User> FlowDraft saveDraft(S session, String id, long expectedLockVersion, String raw) {

        return commandExecutor.execute(session, new SaveFlowDraftCommand(id, expectedLockVersion, raw));
    }

    public <S extends Session<U>, U extends User> Flow deploy(S session, String id) {

        return commandExecutor.execute(session, new DeployFlowCommand(id));
    }

    public <S extends Session<U>, U extends User> FlowDraft deleteDraft(S session, String id) {

        return commandExecutor.execute(session, new DeleteFlowDraftCommand(id));
    }

    public <S extends Session<U>, U extends User> Flow delete(S session, String id) {

        return commandExecutor.execute(session, new DeleteFlowCommand(id));
    }

    public <S extends Session<U>, U extends User> List<FlowDraft> drafts(S session) {

        return queryHandler.drafts(session);
    }

    public <S extends Session<U>, U extends User> Optional<FlowDraft> draft(S session, String flowId) {

        return queryHandler.draft(session, flowId);
    }

    public <S extends Session<U>, U extends User> Optional<Flow> flow(S session, String flowId, long reversion) {

        return queryHandler.flow(session, flowId, reversion);
    }

    public <S extends Session<U>, U extends User> Optional<Flow> latestFlow(S session, String flowId) {

        return queryHandler.latestFlow(session, flowId);
    }
}
