package org.cses.flow.core.services.flows.commands;

import org.cses.flow.core.services.Command;
import org.cses.flow.core.domains.flows.FlowDraft;

public record DeleteFlowDraftCommand(String key)
    implements Command<FlowDraft> {

    public static DeleteFlowDraftCommand from(String key) {
        return new DeleteFlowDraftCommand(key);
    }

    @Override
    public void validate() {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Flow key must not be blank");
        }
    }
}
