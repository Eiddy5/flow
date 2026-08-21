package org.cses.flow.core.services.flows;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.services.flows.commands.DeleteFlowCommand;
import org.cses.flow.core.services.flows.commands.DeleteFlowDraftCommand;
import org.cses.flow.core.services.flows.commands.DeployFlowCommand;
import org.cses.flow.core.services.flows.commands.SaveFlowDraftCommand;
import org.cses.flow.core.services.CommandExecutor;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.queries.flows.FlowQueryHandler;
import org.cses.flow.core.serializers.YamlParser;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.List;
import java.util.Optional;

@Singleton
public final class FlowService {

    private final CommandExecutor commandExecutor;
    private final FlowQueryHandler queryHandler;
    private final YamlParser yamlParser;

    @Inject
    public FlowService(
            CommandExecutor commandExecutor,
            FlowQueryHandler queryHandler,
            YamlParser yamlParser
    ) {
        this.commandExecutor = commandExecutor;
        this.queryHandler = queryHandler;
        this.yamlParser = yamlParser;
    }

    public <S extends Session<U>, U extends User> FlowDraft saveDraft(S session, String raw) {
        return saveDraft(session, keyFromRaw(raw), raw);
    }

    public <S extends Session<U>, U extends User> FlowDraft saveDraft(
            S session,
            String flowKey,
            String raw
    ) {
        return commandExecutor.execute(
                session,
                SaveFlowDraftCommand.from(flowKey, null, raw)
        );
    }

    public <S extends Session<U>, U extends User> FlowDraft saveDraft(
            S session,
            String flowKey,
            Long expectedLockVersion,
            String raw
    ) {

        return commandExecutor.execute(session, SaveFlowDraftCommand.from(
                flowKey,
                expectedLockVersion,
                raw
        ));
    }

    public <S extends Session<U>, U extends User> FlowDraft saveDraft(
            S session,
            SaveFlowDraftCommand command
    ) {
        return commandExecutor.execute(session, command);
    }

    public <S extends Session<U>, U extends User> Flow deploy(
            S session,
            String flowKey
    ) {

        return commandExecutor.execute(
                session,
                DeployFlowCommand.from(flowKey)
        );
    }

    public <S extends Session<U>, U extends User> FlowDraft deleteDraft(
            S session,
            String flowKey
    ) {

        return commandExecutor.execute(
                session,
                DeleteFlowDraftCommand.from(flowKey)
        );
    }

    public <S extends Session<U>, U extends User> Flow delete(
            S session,
            String flowKey
    ) {

        return commandExecutor.execute(
                session,
                DeleteFlowCommand.from(flowKey)
        );
    }

    public <S extends Session<U>, U extends User> List<FlowDraft> drafts(S session) {

        return queryHandler.drafts(session);
    }

    public <S extends Session<U>, U extends User> Optional<FlowDraft> draft(
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

    private String keyFromRaw(String raw) {
        Object key = yamlParser.parse(raw).get("key");
        if (!(key instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(
                    "New FlowDraft requires a non-blank top-level key"
            );
        }
        return text.trim();
    }
}
